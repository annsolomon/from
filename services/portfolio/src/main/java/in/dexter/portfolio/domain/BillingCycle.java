package in.dexter.portfolio.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * A card's statement cycle (MASTER_PLAN P11). The statement is generated on the statement day (1-28, or 31 = the
 * last day of the month); a cycle runs from the day after the previous statement day to the statement day, and the
 * payment is due {@code dueOffsetDays} after it. Days are Asia/Kolkata dates (India-only product, as ledger's A-45).
 * Pure.
 */
public record BillingCycle(LocalDate periodStart, LocalDate periodEnd, LocalDate dueDate) {
  public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  public static final int LAST_DAY = 31;

  public static LocalDate day(Instant t) {
    return LocalDate.ofInstant(t, ZONE);
  }

  public static boolean validStatementDay(int d) {
    return d >= 1 && d <= 28 || d == LAST_DAY;
  }

  /** The statement date of a month: the statement day, or the month's last day for 31. */
  static LocalDate statementDate(YearMonth m, int statementDay) {
    return statementDay == LAST_DAY ? m.atEndOfMonth() : m.atDay(statementDay);
  }

  /** The cycle that contains {@code day}. */
  public static BillingCycle containing(LocalDate day, int statementDay, int dueOffsetDays) {
    if (!validStatementDay(statementDay)) {
      throw new IllegalArgumentException("statement day " + statementDay);
    }
    YearMonth m = YearMonth.from(day);
    LocalDate end = statementDate(m, statementDay);
    if (day.isAfter(end)) {
      end = statementDate(m.plusMonths(1), statementDay);
    }
    LocalDate start = statementDate(YearMonth.from(end).minusMonths(1), statementDay).plusDays(1);
    return new BillingCycle(start, end, end.plusDays(dueOffsetDays));
  }
}
