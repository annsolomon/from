package in.dexter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.core.tenant.Tenant;
import in.dexter.db.plan.PlanAssert;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantTx;
import in.dexter.ledger.app.ExpenseFilter;
import in.dexter.ledger.infra.ExpenseRepository;
import in.dexter.ledger.support.BulkData;
import in.dexter.ledger.support.LedgerIT;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * PlanAssert (MASTER_PLAN 3.5) for GET /v1/expenses on ≥ 1M rows: the unfiltered list and the date range use
 * {@code expenses_list} without a Sort for both the first and the next page, for a heavy account (30,000 rows) and
 * a typical one; card and merchant filters never scan the table or sort.
 */
class ExpensePlanIT extends LedgerIT {
  static final UUID TYPICAL = UUID.nameUUIDFromBytes("x".getBytes());
  static final UUID LAST_ID = UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff");

  @Autowired
  TenantTx tx;
  @Autowired
  JdbcClient jdbc;

  @BeforeAll
  static void bulk() {
    BulkData.ensure();
  }

  @Test
  void theTableHoldsOverAMillionRows() {
    assertThat(ADMIN.sql("SELECT count(1) FROM ledger.expenses").query(Long.class).single())
        .isGreaterThanOrEqualTo(1_000_000);
  }

  String sql(int mask, boolean next) {
    return next ? ExpenseRepository.listQuery(mask).nextPageSql() : ExpenseRepository.listQuery(mask).firstPageSql();
  }

  void plan(UUID account, int mask, Map<String, Object> params, boolean next, String requiredIndex, boolean noSort) {
    Map<String, Object> p = new HashMap<>(params);
    p.put("__limit", next ? 26 : 51);
    if (next) {
      p.put("__k0", Sql.ts(Instant.parse("2026-06-01T00:00:00Z")));
      p.put("__k1", LAST_ID);
    }
    tx.read(Tenant.user(account), () -> {
      PlanAssert a = PlanAssert.query(jdbc, sql(mask, next), p).forbidSeqScanOn("expenses");
      if (noSort) {
        a.forbidSort();
      }
      if (requiredIndex != null) {
        a.requireIndex(requiredIndex);
      }
      return a.verify();
    });
  }

  @Test
  void unfilteredListUsesExpensesListWithoutASort() {
    for (UUID acc : new UUID[] {BulkData.HEAVY, TYPICAL}) {
      plan(acc, 0, Map.of(), false, "expenses_list", true);
      plan(acc, 0, Map.of(), true, "expenses_list", true);
    }
  }

  @Test
  void dateRangeUsesExpensesListWithoutASort() {
    int mask = ExpenseRepository.mask(ExpenseFilter.NONE, Instant.EPOCH, Instant.EPOCH);
    Map<String, Object> p = Map.of("from", Sql.ts(Instant.parse("2026-01-01T00:00:00Z")),
        "to", Sql.ts(Instant.parse("2026-04-01T00:00:00Z")));
    plan(BulkData.HEAVY, mask, p, false, "expenses_list", true);
    plan(BulkData.HEAVY, mask, p, true, "expenses_list", true);
  }

  @Test
  void cardAndMerchantFiltersNeitherScanNorSort() {
    UUID card = UUID.nameUUIDFromBytes("card".getBytes());
    int cardMask = ExpenseRepository.mask(new ExpenseFilter(null, null, null, null, card, null, null, null), null, null);
    plan(BulkData.HEAVY, cardMask, Map.of("cardId", card), false, null, true);
    UUID merchant = UUID.nameUUIDFromBytes("m".getBytes());
    int mMask = ExpenseRepository.mask(new ExpenseFilter(null, null, null, null, null, merchant, null, null), null, null);
    plan(BulkData.HEAVY, mMask, Map.of("merchantId", merchant), false, null, true);
  }

  @Test
  void categorySubtreeNeverScansTheTable() {
    int mask = ExpenseRepository.mask(new ExpenseFilter(null, null, "food", null, null, null, null, null), null, null);
    plan(BulkData.HEAVY, mask, Map.of("category", "food"), false, null, false);
  }
}
