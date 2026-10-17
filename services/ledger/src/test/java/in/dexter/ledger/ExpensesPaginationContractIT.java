package in.dexter.ledger;

import in.dexter.core.tenant.Tenant;
import in.dexter.db.plan.PlanAssert;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantTx;
import in.dexter.ledger.infra.ExpenseRepository;
import in.dexter.ledger.support.BulkData;
import in.dexter.ledger.support.LedgerIT;
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

/** MASTER_PLAN 4.5 on GET /v1/expenses (newest first), with the category filter for the cursor-binding case. */
class ExpensesPaginationContractIT extends LedgerIT {
  static final AtomicLong SEED_HOUR = new AtomicLong();
  static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired
  TenantTx tx;
  @Autowired
  JdbcClient jdbc;

  @Nested
  class Contract extends PaginationContract {
    @Override
    protected void seed(UUID account, int n, Instant at) {
      Instant base = BASE.plus(SEED_HOUR.incrementAndGet(), ChronoUnit.HOURS);
      ADMIN.sql("""
          INSERT INTO ledger.expenses (id, account_id, occurred_at, amount_paise, payment_method, category_path)
          SELECT gen_random_uuid(), :a,
                 COALESCE(CAST(:at AS timestamptz), CAST(:base AS timestamptz) + g * interval '1 millisecond'),
                 100 + g, 'upi', CAST(CASE WHEN g % 2 = 0 THEN 'food.dining' ELSE 'travel.cab' END AS ltree)
          FROM generate_series(1, :n) g
          """).param("a", account).param("at", at == null ? null : Sql.ts(at)).param("base", Sql.ts(base))
          .param("n", n).update();
    }

    @Override
    protected HttpResult list(UUID account, Integer limit, String cursor, Map<String, String> filters) {
      StringBuilder q = new StringBuilder("/v1/expenses?x=1");
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
      return ADMIN.sql("SELECT id::text FROM ledger.expenses WHERE account_id = :a AND deleted_at IS NULL"
          + " ORDER BY occurred_at DESC, id DESC").param("a", account).query(String.class).list();
    }

    @Override
    protected List<Map<String, String>> twoDifferentFilters() {
      return List.of(Map.of("category", "food"), Map.of("category", "travel"));
    }

    @Override
    protected void assertListPlan() {
      BulkData.ensure();
      tx.read(Tenant.user(BulkData.HEAVY), () -> PlanAssert.query(jdbc, ExpenseRepository.listQuery(0).firstPageSql(),
              Map.of("__limit", 51))
          .forbidSeqScanOn("expenses").forbidSort().requireIndex("expenses_list").verify());
      tx.read(Tenant.user(BulkData.HEAVY), () -> PlanAssert.query(jdbc, ExpenseRepository.listQuery(0).nextPageSql(),
              Map.of("__limit", 26, "__k0", Sql.ts(BASE.plus(180, ChronoUnit.DAYS)),
                  "__k1", UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff")))
          .forbidSeqScanOn("expenses").forbidSort().requireIndex("expenses_list").verify());
    }
  }
}
