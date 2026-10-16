package in.dexter.idempotency;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * The Idempotency-Key engine (MASTER_PLAN 5.3, 5.4).
 *
 * <p><b>Mode A</b> ({@link #execute}) — call it from an {@code app/} use-case inside {@code
 * TenantTx.write}; the key row, the business write, the outbox row and the stored response share
 * one transaction:
 * <ol>
 *   <li>{@code INSERT ... 'in_progress' ON CONFLICT DO NOTHING}. A concurrent first request holds the
 *       unique index, so this waits up to the role's {@code lock_timeout} (2s); a timeout becomes
 *       {@code 409 IDEMPOTENCY_IN_PROGRESS} with {@code Retry-After: 1}.</li>
 *   <li>Inserted → run the work, store status/headers/body, return it.</li>
 *   <li>Not inserted → the committed row decides: same fingerprint → replay; different → {@code 422
 *       IDEMPOTENCY_KEY_REUSED}; still {@code in_progress} (a Mode B call) → 409.</li>
 * </ol>
 * A rollback removes the key with everything else, so a retry after a failure is clean.
 *
 * <p><b>Mode B</b> ({@link #beginExternal}, {@link #recordRecoveryPoint}, {@link #completeExternal},
 * {@link #abandonExternal}) — for calls with external side effects (P16 payments). Each method runs in
 * its <em>own</em> {@code TenantTx.write}; the key is committed as {@code in_progress} with a lease
 * ({@code locked_until}) and the last finished phase ({@code recovery_point}). An expired lease can be
 * taken over by the retry, which resumes from the recovery point. The stale-row sweeper is P16.
 */
public final class IdempotencyEngine {
  static final String LOCK_NOT_AVAILABLE = "55P03";
  private static final Map<String, Object> RETRY_AFTER_1 = Map.of("retryAfterSeconds", 1);

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final String insertSql;
  private final String selectSql;
  private final String completeSql;
  private final String takeOverSql;
  private final String recoverySql;
  private final String abandonSql;

  public IdempotencyEngine(JdbcClient jdbc, ObjectMapper mapper, String schema) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    String t = Sql.ident(schema) + ".idempotency_keys";
    this.insertSql = "INSERT INTO " + t + " (account_id, idem_key, request_method, request_path, request_hash, state,"
        + " locked_until) VALUES (:a, :k, :m, :p, :h, 'in_progress',"
        + " CASE WHEN CAST(:lease AS bigint) IS NULL THEN NULL"
        + " ELSE now() + CAST(:lease AS bigint) * interval '1 millisecond' END)"
        + " ON CONFLICT DO NOTHING";
    this.selectSql = "SELECT i.request_method, i.request_path, i.request_hash, i.state, i.response_status,"
        + " i.response_body::text AS body, i.recovery_point, (i.locked_until IS NOT NULL AND i.locked_until > now()) AS leased,"
        + " COALESCE((SELECT array_agg(e.key ORDER BY e.key) FROM jsonb_each_text(i.response_headers) e), '{}') AS hk,"
        + " COALESCE((SELECT array_agg(e.value ORDER BY e.key) FROM jsonb_each_text(i.response_headers) e), '{}') AS hv"
        + " FROM " + t + " i WHERE i.account_id = :a AND i.idem_key = :k";
    this.completeSql = "UPDATE " + t + " SET state = 'completed', response_status = :st,"
        + " response_body = CAST(:b AS jsonb), response_headers = CAST(:hd AS jsonb), locked_until = NULL"
        + " WHERE account_id = :a AND idem_key = :k AND state = 'in_progress' RETURNING response_body::text";
    this.takeOverSql = "UPDATE " + t + " SET locked_until = now() + CAST(:lease AS bigint) * interval '1 millisecond'"
        + " WHERE account_id = :a AND idem_key = :k AND state = 'in_progress'"
        + " AND (locked_until IS NULL OR locked_until <= now()) RETURNING coalesce(recovery_point, '')";
    this.recoverySql = "UPDATE " + t + " SET recovery_point = :rp"
        + " WHERE account_id = :a AND idem_key = :k AND state = 'in_progress'";
    this.abandonSql = "DELETE FROM " + t + " WHERE account_id = :a AND idem_key = :k AND state = 'in_progress'";
  }

  // ------------------------------------------------------------------------------------ Mode A

  /**
   * Runs {@code work} at most once per {@code (tenant, key)} (Mode A). A null {@code key} runs the
   * work normally (the key is optional on that endpoint). Must be called inside {@code TenantTx.write}
   * for {@code tenant}.
   */
  public IdempotentResponse execute(Tenant tenant, @Nullable UUID key, IdempotentRequest request,
      Supplier<ApiResponse> work) {
    requireWriteTx(tenant);
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(work, "work");
    if (key == null) {
      ApiResponse r = Objects.requireNonNull(work.get(), "work returned null");
      return new IdempotentResponse(r.status(), r.headers(), bodyJson(r.body()), false);
    }
    if (tryInsert(tenant, key, request, null)) {
      ApiResponse r = Objects.requireNonNull(work.get(), "work returned null");
      return store(tenant, key, r);
    }
    Stored s = load(tenant, key).orElseThrow(IdempotencyEngine::inProgress);
    requireSameRequest(s, request);
    if (!"completed".equals(s.state())) {
      throw inProgress();
    }
    return s.replay();
  }

  // ------------------------------------------------------------------------------------ Mode B

  /** Result of {@link #beginExternal}: either go ahead (from {@code recoveryPoint}) or replay. */
  public sealed interface ExternalStart permits Proceed, Replay {}

  /** Go ahead. {@code recoveryPoint} is null for a fresh key, else the last phase a previous attempt finished. */
  public record Proceed(@Nullable String recoveryPoint) implements ExternalStart {}

  /** Already completed: send this response. */
  public record Replay(IdempotentResponse response) implements ExternalStart {}

  /**
   * Phase 1 of Mode B, in its own {@code TenantTx.write}: reserves the key with a lease. A key whose
   * lease expired (the previous attempt died) is taken over and resumes from its recovery point.
   */
  public ExternalStart beginExternal(Tenant tenant, UUID key, IdempotentRequest request, Duration lease) {
    requireWriteTx(tenant);
    Objects.requireNonNull(key, "key");
    if (lease == null || lease.isNegative() || lease.isZero() || lease.toHours() > 24) {
      throw new IllegalArgumentException("lease must be in (0, 24h]");
    }
    if (tryInsert(tenant, key, request, lease)) {
      return new Proceed(null);
    }
    Stored s = load(tenant, key).orElseThrow(IdempotencyEngine::inProgress);
    requireSameRequest(s, request);
    if ("completed".equals(s.state())) {
      return new Replay(s.replay());
    }
    if (s.leased()) {
      throw inProgress();
    }
    String rp = jdbc.sql(takeOverSql).param("a", tenant.accountId()).param("k", key).param("lease", lease.toMillis())
        .query(String.class).optional().orElseThrow(IdempotencyEngine::inProgress);
    return new Proceed(rp.isEmpty() ? null : rp);
  }

  /** Stores the last finished phase of a Mode B call (own transaction). */
  public void recordRecoveryPoint(Tenant tenant, UUID key, String recoveryPoint) {
    requireWriteTx(tenant);
    if (recoveryPoint == null || !recoveryPoint.matches("[a-z0-9_:.-]{1,64}")) {
      throw new IllegalArgumentException("bad recovery point");
    }
    int n = jdbc.sql(recoverySql).param("a", tenant.accountId()).param("k", key).param("rp", recoveryPoint).update();
    if (n != 1) {
      throw new IllegalStateException("no in-progress idempotency key " + key);
    }
  }

  /** Final phase of a Mode B call (own transaction): stores the response the key will replay. */
  public IdempotentResponse completeExternal(Tenant tenant, UUID key, ApiResponse response) {
    requireWriteTx(tenant);
    return store(tenant, key, response);
  }

  /** Mode B definite failure before any external effect: removes the key so a retry starts clean. */
  public void abandonExternal(Tenant tenant, UUID key) {
    requireWriteTx(tenant);
    jdbc.sql(abandonSql).param("a", tenant.accountId()).param("k", key).update();
  }

  // ------------------------------------------------------------------------------------ internals

  private boolean tryInsert(Tenant tenant, UUID key, IdempotentRequest request, @Nullable Duration lease) {
    try {
      return jdbc.sql(insertSql)
          .param("a", tenant.accountId()).param("k", key)
          .param("m", request.method()).param("p", request.path()).param("h", request.hash())
          .param("lease", lease == null ? null : lease.toMillis())
          .update() == 1;
    } catch (DataAccessException e) {
      if (isLockTimeout(e)) {
        // The first request with this key is still running; this transaction is now aborted.
        throw new DexterException(CoreErrors.IDEMPOTENCY_IN_PROGRESS,
            "A request with this Idempotency-Key is still being processed; retry shortly", List.of(),
            RETRY_AFTER_1, e);
      }
      throw e;
    }
  }

  private IdempotentResponse store(Tenant tenant, UUID key, ApiResponse r) {
    String stored = jdbc.sql(completeSql)
        .param("a", tenant.accountId()).param("k", key)
        .param("st", r.status())
        .param("b", bodyJson(r.body()))
        .param("hd", headersJson(r.headers()))
        .query(String.class).optional()
        .orElseThrow(() -> new IllegalStateException("idempotency key " + key + " is not in progress"));
    return new IdempotentResponse(r.status(), r.headers(), stored, false);
  }

  private Optional<Stored> load(Tenant tenant, UUID key) {
    return jdbc.sql(selectSql).param("a", tenant.accountId()).param("k", key)
        .query((rs, i) -> {
          String[] hk = Rows.textArray(rs, "hk");
          String[] hv = Rows.textArray(rs, "hv");
          Map<String, String> headers = new LinkedHashMap<>();
          for (int j = 0; j < Math.min(hk.length, hv.length); j++) {
            headers.put(hk[j], hv[j]);
          }
          return new Stored(rs.getString("request_method"), rs.getString("request_path"), rs.getBytes("request_hash"),
              rs.getString("state"), Rows.nullableInt(rs, "response_status"), rs.getString("body"), headers,
              rs.getBoolean("leased"));
        })
        .optional();
  }

  private static void requireSameRequest(Stored s, IdempotentRequest request) {
    if (!request.sameAs(s.method(), s.path(), s.hash())) {
      throw new DexterException(CoreErrors.IDEMPOTENCY_KEY_REUSED,
          "This Idempotency-Key was already used with a different request");
    }
  }

  private static DexterException inProgress() {
    return new DexterException(CoreErrors.IDEMPOTENCY_IN_PROGRESS,
        "A request with this Idempotency-Key is still being processed; retry shortly", List.of(),
        RETRY_AFTER_1, null);
  }

  private static void requireWriteTx(Tenant tenant) {
    Objects.requireNonNull(tenant, "tenant");
    Tenant bound = TenantScope.require();
    if (!bound.equals(tenant)) {
      throw new IllegalStateException("IdempotencyEngine called for a different tenant than the TenantTx");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("IdempotencyEngine needs TenantTx.write, not read");
    }
  }

  private @Nullable String bodyJson(@Nullable Object body) {
    if (body == null) {
      return null;
    }
    return mapper.writeValueAsString(body);
  }

  private static String headersJson(Map<String, String> headers) {
    return CanonicalJson.write(headers);
  }

  static boolean isLockTimeout(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
        return true;
      }
      if (c.getCause() == c) {
        break;
      }
    }
    return false;
  }

  private record Stored(String method, String path, byte[] hash, String state, @Nullable Integer status,
      @Nullable String body, Map<String, String> headers, boolean leased) {
    IdempotentResponse replay() {
      return new IdempotentResponse(status == null ? 200 : status, headers, body, true);
    }
  }
}
