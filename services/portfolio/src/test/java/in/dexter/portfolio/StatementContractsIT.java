package in.dexter.portfolio;

import in.dexter.core.tenant.Tenant;
import in.dexter.db.plan.PlanAssert;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantTx;
import in.dexter.portfolio.app.StatementService;
import in.dexter.portfolio.infra.StatementRepository;
import in.dexter.portfolio.support.PortfolioIT;
import in.dexter.testkit.contract.HttpResult;
import in.dexter.testkit.contract.IdempotencyContract;
import in.dexter.testkit.contract.PaginationContract;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * MASTER_PLAN 4.5 on GET /v1/statements/uploads (newest first) with its plan on 60,000 uploads, and 5.5 on
 * POST /v1/statements/uploads (Idempotency-Key required).
 */
class StatementContractsIT extends PortfolioIT {
  static final Map<UUID, UUID> CARDS = new ConcurrentHashMap<>();
  static final AtomicLong SEED_HOUR = new AtomicLong();
  static final Instant BASE = Instant.parse("2026-02-01T00:00:00Z");
  static final UUID HEAVY = UUID.fromString("0000000a-0000-7000-8000-0000000f00d5");
  static volatile boolean bulk;

  @Autowired
  TenantTx tx;
  @Autowired
  JdbcClient jdbc;
  @Autowired
  StatementService statements;

  static UUID cardOf(UUID account) {
    return CARDS.computeIfAbsent(account, a -> {
      UUID id = UUID.randomUUID();
      ADMIN.sql("INSERT INTO portfolio.user_cards (id, account_id, card_product_id, nickname, last4, network,"
          + " statement_day) VALUES (:id, :a, :p, 'Contract', '0001', 'visa', 15)").param("id", id).param("a", a)
          .param("p", POINTS_CARD).update();
      return id;
    });
  }

  static synchronized void ensureBulk() {
    if (bulk) {
      return;
    }
    ADMIN.sql("INSERT INTO portfolio.user_cards (id, account_id, card_product_id, nickname, last4, network,"
        + " statement_day) SELECT md5('up-card' || g)::uuid, md5('up-acct' || g)::uuid, :p, 'Bulk', '0001', 'visa',"
        + " 15 FROM generate_series(0, 2000) g ON CONFLICT DO NOTHING").param("p", POINTS_CARD).update();
    ADMIN.sql("UPDATE portfolio.user_cards SET account_id = :h WHERE id = md5('up-card0')::uuid").param("h", HEAVY)
        .update();
    ADMIN.sql("""
        INSERT INTO portfolio.statement_uploads (id, account_id, user_card_id, file_name, quarantine_key, created_at)
        SELECT gen_random_uuid(), md5('up-acct' || (g % 2000 + 1))::uuid, md5('up-card' || (g % 2000 + 1))::uuid,
               'bulk.pdf', 'q', TIMESTAMPTZ '2025-01-01' + g * interval '7 minutes'
        FROM generate_series(1, 60000) g
        """).update();
    ADMIN.sql("""
        INSERT INTO portfolio.statement_uploads (id, account_id, user_card_id, file_name, quarantine_key, created_at)
        SELECT gen_random_uuid(), CAST(:h AS uuid), md5('up-card0')::uuid, 'heavy.pdf', 'q',
               TIMESTAMPTZ '2025-01-01' + g * interval '1 hour'
        FROM generate_series(1, 3000) g
        """).param("h", HEAVY).update();
    ADMIN.sql("ANALYZE portfolio.statement_uploads").update();
    bulk = true;
  }

  HttpResult send(String path, UUID account, UUID key, String body) {
    Res r = key == null ? send("POST", path, body, "application/json", auth(account))
        : send("POST", path, body, "application/json", auth(account, "Idempotency-Key", key.toString()));
    return new HttpResult(r.status(), r.headers(), r.body());
  }

  @Nested
  class ListUploads extends PaginationContract {
    @Override
    protected void seed(UUID account, int n, Instant at) {
      Instant base = BASE.plus(SEED_HOUR.incrementAndGet(), ChronoUnit.HOURS);
      ADMIN.sql("""
          INSERT INTO portfolio.statement_uploads (id, account_id, user_card_id, file_name, quarantine_key,
                                                   created_at)
          SELECT gen_random_uuid(), :a, :c, 'contract.pdf', 'q',
                 COALESCE(CAST(:at AS timestamptz), CAST(:base AS timestamptz) + g * interval '1 millisecond')
          FROM generate_series(1, :n) g
          """).param("a", account).param("c", cardOf(account)).param("at", at == null ? null : Sql.ts(at))
          .param("base", Sql.ts(base)).param("n", n).update();
    }

    @Override
    protected HttpResult list(UUID account, Integer limit, String cursor, Map<String, String> filters) {
      StringBuilder q = new StringBuilder("/v1/statements/uploads?x=1");
      if (limit != null) {
        q.append("&limit=").append(limit);
      }
      if (cursor != null) {
        q.append("&cursor=").append(java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8));
      }
      filters.forEach((k, v) -> q.append('&').append(k).append('=').append(v));
      Res r = get(q.toString(), account);
      return new HttpResult(r.status(), r.headers(), r.body());
    }

    @Override
    protected List<String> expectedOrder(UUID account) {
      return ADMIN.sql("SELECT id::text FROM portfolio.statement_uploads WHERE account_id = :a"
          + " ORDER BY created_at DESC, id DESC").param("a", account).query(String.class).list();
    }

    @Override
    protected List<Map<String, String>> twoDifferentFilters() {
      return null;
    }

    @Override
    protected void assertListPlan() {
      ensureBulk();
      tx.read(Tenant.user(HEAVY), () -> PlanAssert.query(jdbc, StatementRepository.list(0).firstPageSql(),
              Map.of("__limit", 51))
          .forbidSeqScanOn("statement_uploads").forbidSort().requireIndex("statement_uploads_list").verify());
      tx.read(Tenant.user(HEAVY), () -> PlanAssert.query(jdbc, StatementRepository.list(1).nextPageSql(),
              Map.of("__limit", 26, "card", UUID.nameUUIDFromBytes(new byte[0]),
                  "__k0", Sql.ts(Instant.parse("2025-03-01T00:00:00Z")),
                  "__k1", UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff")))
          .forbidSeqScanOn("statement_uploads").forbidSort().verify());
    }
  }

  @Nested
  class CreateUpload extends IdempotencyContract {
    /** The body names the account's own card (a placeholder in {@link #body} is filled per account). */
    @Override
    protected HttpResult mutate(UUID account, UUID key, String body) {
      return send("/v1/statements/uploads", account, key, body.replace("__CARD__", cardOf(account).toString()));
    }

    @Override
    protected String body(int n) {
      return "{\"userCardId\":\"__CARD__\",\"fileName\":\"statement-" + n + ".pdf\"}";
    }

    /** Creating an upload writes no event (the statement event comes with :submit). */
    @Override
    protected boolean writesEvent() {
      return false;
    }

    @Override
    protected long businessRows(UUID account) {
      return ADMIN.sql("SELECT count(1) FROM portfolio.statement_uploads WHERE account_id = :a").param("a", account)
          .query(Long.class).single();
    }

    @Override
    protected long outboxRows(UUID account) {
      return PortfolioIT.outboxRows(account, "portfolio.statement.submitted");
    }

    @Override
    protected void failNextMutation() {
      statements.failNextCreate();
    }
  }
}
