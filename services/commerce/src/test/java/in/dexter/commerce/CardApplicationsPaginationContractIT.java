package in.dexter.commerce;

import in.dexter.commerce.infra.CommerceRepository;
import in.dexter.commerce.support.CommerceIT;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.plan.PlanAssert;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantTx;
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

/**
 * MASTER_PLAN 4.5 on GET /v1/card-applications (newest first), plus the list plan on 60,000 applications of 1,000
 * accounts (card_applications_list, no Sort). Seeded rows are withdrawn so the one-open-per-card index never
 * meets them.
 */
class CardApplicationsPaginationContractIT extends CommerceIT {
  static final AtomicLong SEED_HOUR = new AtomicLong();
  static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
  static final UUID HEAVY = UUID.fromString("0000000a-0000-7000-8000-0000000c111e");
  static volatile boolean bulk;

  @Autowired
  TenantTx tx;
  @Autowired
  JdbcClient jdbc;
  @Autowired
  CommerceRepository repo;

  static final String SEED = """
      INSERT INTO commerce.card_applications (id, account_id, card_product_id, status, status_history, created_at,
                                              updated_at)
      SELECT gen_random_uuid(), CAST(:a AS uuid), gen_random_uuid(), 'withdrawn', '[]'::jsonb,
             COALESCE(CAST(:at AS timestamptz), CAST(:base AS timestamptz) + g * interval '1 millisecond'), now()
      FROM generate_series(1, :n) g
      """;

  static void seedFor(UUID account, int n, Instant at, Instant base) {
    ADMIN.sql(SEED).param("a", account).param("n", n).param("at", at == null ? null : Sql.ts(at))
        .param("base", Sql.ts(base)).update();
  }

  static synchronized void ensureBulk() {
    if (bulk) {
      return;
    }
    for (int i = 1; i <= 1000; i++) {
      seedFor(UUID.nameUUIDFromBytes(("bulk-application-acct-" + i).getBytes()), 60, null,
          Instant.parse("2025-01-01T00:00:00Z").plus(i, ChronoUnit.MINUTES));
    }
    seedFor(HEAVY, 60, null, Instant.parse("2025-06-01T00:00:00Z"));
    ADMIN.sql("ANALYZE commerce.card_applications").update();
    bulk = true;
  }

  @Nested
  class Contract extends PaginationContract {
    @Override
    protected void seed(UUID account, int n, Instant at) {
      seedFor(account, n, at, BASE.plus(SEED_HOUR.incrementAndGet(), ChronoUnit.HOURS));
    }

    @Override
    protected HttpResult list(UUID account, Integer limit, String cursor, Map<String, String> filters) {
      StringBuilder q = new StringBuilder("/v1/card-applications?x=1");
      if (limit != null) {
        q.append("&limit=").append(limit);
      }
      if (cursor != null) {
        q.append("&cursor=").append(enc(cursor));
      }
      Res r = get(q.toString(), account);
      return new HttpResult(r.status(), r.headers(), r.body());
    }

    @Override
    protected List<String> expectedOrder(UUID account) {
      return ADMIN.sql("SELECT id::text FROM commerce.card_applications WHERE account_id = :a"
          + " ORDER BY created_at DESC, id DESC").param("a", account).query(String.class).list();
    }

    @Override
    protected List<Map<String, String>> twoDifferentFilters() {
      return null;
    }

    @Override
    protected void assertListPlan() {
      ensureBulk();
      tx.read(Tenant.user(HEAVY), () -> PlanAssert.query(jdbc, repo.applicationList.firstPageSql(),
              Map.of("__limit", 51))
          .forbidSeqScanOn("card_applications").forbidSort().requireIndex("card_applications_list").verify());
      tx.read(Tenant.user(HEAVY), () -> PlanAssert.query(jdbc, repo.applicationList.nextPageSql(),
              Map.of("__limit", 26, "__k0", Sql.ts(Instant.parse("2025-06-01T00:00:00.030Z")),
                  "__k1", UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff")))
          .forbidSeqScanOn("card_applications").forbidSort().requireIndex("card_applications_list").verify());
    }
  }
}
