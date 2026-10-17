package in.dexter.ledger.app;

import in.dexter.ledger.domain.DailyDelta;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@code ledger.expense_daily_totals}: delta upserts in the writing transaction, range reads. */
public interface DailyTotalsStore {
  record DayTotal(LocalDate day, String categoryRoot, long totalPaise, int txnCount) {}

  void apply(UUID accountId, List<DailyDelta.Change> changes);

  List<DayTotal> range(UUID accountId, LocalDate from, LocalDate to);
}
