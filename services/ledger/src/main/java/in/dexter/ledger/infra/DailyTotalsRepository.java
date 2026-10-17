package in.dexter.ledger.infra;

import in.dexter.db.sql.Rows;
import in.dexter.db.tenant.TenantScope;
import in.dexter.ledger.app.DailyTotalsStore;
import in.dexter.ledger.domain.DailyDelta;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Delta upserts on {@code ledger.expense_daily_totals} (MASTER_PLAN P07), in the expense write's transaction. A
 * bucket that reaches zero transactions is removed, so the table always equals {@code SUM} over live expenses.
 * Concurrent writes to one bucket serialise on its row lock.
 */
public final class DailyTotalsRepository implements DailyTotalsStore {
  private final JdbcClient jdbc;

  public DailyTotalsRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void apply(UUID accountId, List<DailyDelta.Change> changes) {
    TenantScope.require();
    for (DailyDelta.Change c : changes) {
      jdbc.sql("INSERT INTO ledger.expense_daily_totals (account_id, day, category_root, total_paise, txn_count)"
              + " VALUES (:a, :d, CAST(:r AS public.ltree), :amt, :n)"
              + " ON CONFLICT (account_id, day, category_root) DO UPDATE"
              + " SET total_paise = ledger.expense_daily_totals.total_paise + EXCLUDED.total_paise,"
              + " txn_count = ledger.expense_daily_totals.txn_count + EXCLUDED.txn_count")
          .param("a", accountId).param("d", c.day()).param("r", c.categoryRoot()).param("amt", c.amountPaise())
          .param("n", c.count())
          .update();
      if (c.count() < 0) {
        jdbc.sql("DELETE FROM ledger.expense_daily_totals WHERE account_id = :a AND day = :d"
                + " AND category_root = CAST(:r AS public.ltree) AND txn_count = 0")
            .param("a", accountId).param("d", c.day()).param("r", c.categoryRoot()).update();
      }
    }
  }

  @Override
  public List<DayTotal> range(UUID accountId, LocalDate from, LocalDate to) {
    TenantScope.require();
    return jdbc.sql("SELECT day, category_root::text AS category_root, total_paise, txn_count"
            + " FROM ledger.expense_daily_totals WHERE account_id = :a AND day BETWEEN :f AND :t"
            + " ORDER BY day, category_root")
        .param("a", accountId).param("f", from).param("t", to)
        .query((rs, i) -> new DayTotal(Rows.date(rs, "day"), rs.getString("category_root"), rs.getLong("total_paise"),
            rs.getInt("txn_count")))
        .list();
  }
}
