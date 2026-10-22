package in.dexter.portfolio.app;

import java.time.LocalDate;
import java.util.UUID;

/** API representation of a statement cycle. */
public record StatementView(UUID id, UUID userCardId, LocalDate periodStart, LocalDate periodEnd, LocalDate dueDate,
    long debitsPaise, long creditsPaise, int txnCount, long totalDuePaise, long minDuePaise, String status,
    int version) {

  static StatementView of(TransactionStore.StatementRow s) {
    return new StatementView(s.id(), s.userCardId(), s.periodStart(), s.periodEnd(), s.dueDate(), s.debitsPaise(),
        s.creditsPaise(), s.txnCount(), s.totalDuePaise(), s.minDuePaise(), s.status(), s.version());
  }
}
