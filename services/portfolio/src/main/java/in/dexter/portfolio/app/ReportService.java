package in.dexter.portfolio.app;

import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.portfolio.app.CardStore.CardRow;
import in.dexter.portfolio.app.TransactionStore.Savings;
import in.dexter.portfolio.domain.BillingCycle;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /v1/portfolio/report?period=month|year} (MASTER_PLAN P11): per card and in total, the spend, rewards
 * value earned, fees and interest paid, the card's annual fee for the period (a month carries 1/12), and the net:
 * rewards − fees − annual fee. Built from {@code card_savings_summary} (kept in the recording transaction).
 */
public final class ReportService {
  public record CardReport(UUID userCardId, String nickname, String productName, long spendPaise, int txnCount,
      long rewardsValuePaise, long feesPaise, long annualFeePaise, long netPaise) {}

  public record Report(String period, LocalDate from, LocalDate to, long spendPaise, int txnCount,
      long rewardsValuePaise, long feesPaise, long annualFeePaise, long netPaise, List<CardReport> cards) {}

  private final TenantTx tx;
  private final CardStore cards;
  private final TransactionStore txns;
  private final Clock clock;

  public ReportService(TenantTx tx, CardStore cards, TransactionStore txns, Clock clock) {
    this.tx = tx;
    this.cards = cards;
    this.txns = txns;
    this.clock = clock;
  }

  /** {@code month} = YYYY-MM (period month), {@code year} = YYYY (period year); both default to now. */
  public Report report(Tenant tenant, String period, String month, String year) {
    String p = period == null ? "month" : period;
    LocalDate today = BillingCycle.day(clock.instant());
    LocalDate from;
    LocalDate to;
    int months;
    try {
      if ("month".equals(p)) {
        YearMonth m = month == null ? YearMonth.from(today) : YearMonth.parse(month);
        from = m.atDay(1);
        to = m.atEndOfMonth();
        months = 1;
      } else if ("year".equals(p)) {
        Year y = year == null ? Year.from(today) : Year.parse(year);
        from = y.atDay(1);
        to = y.atMonth(12).atEndOfMonth();
        months = 12;
      } else {
        throw new ValidationException(List.of(new FieldError("period", "INVALID", "month or year")));
      }
    } catch (DateTimeParseException e) {
      throw new ValidationException(List.of(new FieldError("month".equals(p) ? "month" : "year", "INVALID",
          "month=YYYY-MM or year=YYYY")));
    }
    LocalDate f = from;
    LocalDate t = to;
    return tx.read(tenant, () -> {
      Map<UUID, long[]> sums = new LinkedHashMap<>(); // spend, count, rewards, fees
      for (Savings s : txns.savings(f, t.withDayOfMonth(1))) {
        long[] a = sums.computeIfAbsent(s.userCardId(), k -> new long[4]);
        a[0] += s.spendPaise();
        a[1] += s.txnCount();
        a[2] += s.rewardsValuePaise();
        a[3] += s.feesPaise();
      }
      List<CardReport> out = new ArrayList<>();
      long[] total = new long[5];
      for (Map.Entry<UUID, long[]> e : sums.entrySet()) {
        CardRow c = cards.find(e.getKey()).orElse(null);
        CardStore.Product prod = c == null ? null : cards.product(c.cardProductId()).orElse(null);
        long annual = prod == null ? 0 : prod.annualFeePaise() * months / 12;
        long[] a = e.getValue();
        long net = a[2] - a[3] - annual;
        out.add(new CardReport(e.getKey(), c == null ? null : c.nickname(), prod == null ? null : prod.name(), a[0],
            (int) a[1], a[2], a[3], annual, net));
        total[0] += a[0];
        total[1] += a[1];
        total[2] += a[2];
        total[3] += a[3];
        total[4] += annual;
      }
      return new Report(p, f, t, total[0], (int) total[1], total[2], total[3], total[4],
          total[2] - total[3] - total[4], out);
    });
  }
}
