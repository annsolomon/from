package in.dexter.planning;

import in.dexter.core.tenant.Tenant;
import in.dexter.db.plan.PlanAssert;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantTx;
import in.dexter.planning.infra.BudgetRepository;
import in.dexter.planning.support.PlanningIT;
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
 * MASTER_PLAN 4.5 on GET /v1/budgets (newest first), plus the list plan on 60,000 budgets of 1,000 accounts
 * (budgets_list, no Sort).
 */
class BudgetsPaginationContractIT extends PlanningIT {
  static final AtomicLong SEED_HOUR = new AtomicLong();
  static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
  /** The account the plan assertions run as: 60 budgets of its own among 60,000. */
  static final UUID HEAVY = UUID.fromString("0000000a-0000-7000-8000-0000000b0d9e");
  static volatile boolean bulk;

  @Autowired
  TenantTx tx;
  @Autowired
  JdbcClient jdbc;

  static synchronized void ensureBulk() {
    if (bulk) {
      return;
    }
    ADMIN.sql("""
        INSERT INTO planning.budgets (id, account_id, name, period, limit_paise, starts_on, created_at)
        SELECT gen_random_uuid(), md5('bulk-plan-acct' || (g % 1000 + 1))::uuid, 'Bulk', 'monthly', 100000 + g,
               DATE '2026-01-01', TIMESTAMPTZ '2025-01-01' + g * interval '97 seconds'
        FROM generate_series(1, 60000) g
        """).update();
    ADMIN.sql("""
        INSERT INTO planning.budgets (id, account_id, name, period, limit_paise, starts_on, created_at)
        SELECT gen_random_uuid(), CAST(:a AS uuid), 'Heavy', 'monthly', 100000 + g, DATE '2026-01-01',
               TIMESTAMPTZ '2025-06-01' + g * interval '1 hour'
        FROM generate_series(1, 60) g
        """).param("a", HEAVY).update();
    ADMIN.sql("ANALYZE planning.budgets").update();
    bulk = true;
  }

  @Nested
  class Contract extends PaginationContract {
    @Override
    protected void seed(UUID account, int n, Instant at) {
      Instant base = BASE.plus(SEED_HOUR.incrementAndGet(), ChronoUnit.HOURS);
      ADMIN.sql("""
          INSERT INTO planning.budgets (id, account_id, name, period, limit_paise, starts_on, created_at)
          SELECT gen_random_uuid(), :a, 'Contract', 'monthly', 100000 + g, DATE '2026-01-01',
                 COALESCE(CAST(:at AS timestamptz), CAST(:base AS timestamptz) + g * interval '1 millisecond')
          FROM generate_series(1, :n) g
          """).param("a", account).param("at", at == null ? null : Sql.ts(at)).param("base", Sql.ts(base))
          .param("n", n).update();
    }

    @Override
    protected HttpResult list(UUID account, Integer limit, String cursor, Map<String, String> filters) {
      StringBuilder q = new StringBuilder("/v1/budgets?x=1");
      if (limit != null) {
        q.append("&limit=").append(limit);
      }
      if (cursor != null) {
        q.append("&cursor=").append(java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8));
      }
      Res r = get(q.toString(), account);
      return new HttpResult(r.status(), r.headers(), r.body());
    }

    @Override
    protected List<String> expectedOrder(UUID account) {
      return ADMIN.sql("SELECT id::text FROM planning.budgets WHERE account_id = :a"
          + " ORDER BY created_at DESC, id DESC").param("a", account).query(String.class).list();
    }

    @Override
    protected List<Map<String, String>> twoDifferentFilters() {
      return null;
    }

    @Override
    protected void assertListPlan() {
      ensureBulk();
      tx.read(Tenant.user(HEAVY), () -> PlanAssert.query(jdbc, BudgetRepository.LIST.firstPageSql(),
              Map.of("__limit", 51))
          .forbidSeqScanOn("budgets").forbidSort().requireIndex("budgets_list").verify());
      tx.read(Tenant.user(HEAVY), () -> PlanAssert.query(jdbc, BudgetRepository.LIST.nextPageSql(),
              Map.of("__limit", 26, "__k0", Sql.ts(Instant.parse("2025-06-15T00:00:00Z")),
                  "__k1", UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff")))
          .forbidSeqScanOn("budgets").forbidSort().requireIndex("budgets_list").verify());
    }
  }
}
