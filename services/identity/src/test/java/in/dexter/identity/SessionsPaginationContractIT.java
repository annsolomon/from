package in.dexter.identity;

import in.dexter.core.tenant.Tenant;
import in.dexter.db.plan.PlanAssert;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantTx;
import in.dexter.identity.app.AccessTokens;
import in.dexter.identity.infra.SessionRepository;
import in.dexter.identity.support.IdentityIT;
import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.contract.PaginationContract;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** MASTER_PLAN 4.5 on GET /v1/me/sessions (open sessions, newest first). */
class SessionsPaginationContractIT extends IdentityIT {
  static final AtomicLong SEED_HOUR = new AtomicLong();
  static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired
  AccessTokens tokens;
  @Autowired
  TenantTx tx;
  @Autowired
  JdbcClient jdbc;

  @Nested
  class Contract extends PaginationContract {
    @Override
    protected void seed(UUID account, int n, Instant at) {
      Instant base = BASE.plus(SEED_HOUR.incrementAndGet(), ChronoUnit.HOURS);
      ADMIN.sql("INSERT INTO identity.accounts (id, email, password_hash, status) VALUES (:a, :e, repeat('h', 40),"
              + " 'active') ON CONFLICT (id) DO NOTHING")
          .param("a", account).param("e", "page-" + account + "@it.test").update();
      ADMIN.sql("""
          INSERT INTO identity.sessions (id, account_id, client_id, auth_time, created_at, last_seen_at)
          SELECT gen_random_uuid(), :a, 'dexter-app', now(),
                 COALESCE(CAST(:at AS timestamptz), CAST(:base AS timestamptz) + g * interval '1 millisecond'), now()
          FROM generate_series(1, :n) g
          """).param("a", account).param("at", Sql.ts(at)).param("base", Sql.ts(base)).param("n", n).update();
    }

    @Override
    protected HttpResult list(UUID account, Integer limit, String cursor, Map<String, String> filters) {
      StringBuilder q = new StringBuilder("/v1/me/sessions?x=1");
      if (limit != null) {
        q.append("&limit=").append(limit);
      }
      if (cursor != null) {
        q.append("&cursor=").append(java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8));
      }
      String token = tokens.forUser(account, UUID.randomUUID(), "dexter-app", List.of("pwd"), Instant.now(),
          "Asia/Kolkata", "FREE", false).token();
      Res r = get(q.toString(), bearer(token));
      return new HttpResult(r.status(), r.headers(), r.body());
    }

    @Override
    protected List<String> expectedOrder(UUID account) {
      return ADMIN.sql("SELECT id::text FROM identity.sessions WHERE account_id = :a AND revoked_at IS NULL"
          + " ORDER BY created_at DESC, id DESC").param("a", account).query(String.class).list();
    }

    @Override
    protected List<Map<String, String>> twoDifferentFilters() {
      return null; // the sessions list has no filters
    }

    @Override
    protected void assertListPlan() {
      UUID bulk = UUID.fromString("0000b01c-0000-7000-8000-000000000004");
      Long have = ADMIN.sql("SELECT count(1) FROM identity.sessions WHERE account_id = :a").param("a", bulk)
          .query(Long.class).single();
      if (have < 30_000) {
        seed(bulk, 30_000, null);
        ADMIN.sql("ANALYZE identity.sessions").update();
      }
      Tenant t = Tenant.user(bulk);
      tx.read(t, () -> PlanAssert.query(jdbc, SessionRepository.LIST.firstPageSql(), Map.of("__limit", 51))
          .forbidSeqScanOn("sessions").forbidSort().requireIndex("sessions_active_list").verify());
      tx.read(t, () -> PlanAssert.query(jdbc, SessionRepository.LIST.nextPageSql(), Map.of("__limit", 26,
              "__k0", Sql.ts(BASE.plus(1, ChronoUnit.DAYS)), "__k1", UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff")))
          .forbidSeqScanOn("sessions").forbidSort().requireIndex("sessions_active_list").verify());
    }
  }
}
