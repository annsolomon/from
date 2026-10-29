package in.dexter.portfolio.calc;

import in.dexter.portfolio.calc.CreditUtilizationCalculator.OverlimitFeeTerms;
import in.dexter.portfolio.calc.LateFeeAndDelinquencyCalculator.DelinquencyClassification;
import in.dexter.portfolio.calc.LateFeeAndDelinquencyCalculator.LateFeeTier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * What happens if the statement is simply ignored, projected to 30, 60 and 90 days overdue (old repo
 * {@code calc/NoPaymentSimulator}, itself a port of {@code simulators.js simulateNoPayment}).
 *
 * <p>Ties three engines together: {@link LedgerEngine} for interest accrual,
 * {@link LateFeeAndDelinquencyCalculator} for the late fee and the RBI staging, and
 * {@link CreditUtilizationCalculator} for the recurring over-limit fee.
 */
public final class NoPaymentSimulator {

  /** The anchor the original used for a dateless projection; only the day count matters. */
  static final LocalDate SIMULATION_EPOCH = LocalDate.of(2026, 1, 1);

  private static final int[] PROJECTION_DAYS = {30, 60, 90};

  private NoPaymentSimulator() {}

  /** @param overlimitFeesAccruedPaise the over-limit fee charged once per elapsed cycle, with GST */
  public record Projection(int daysOverdue, long interestAccruedPaise, long overlimitFeesAccruedPaise,
      long totalOutstandingPaise, DelinquencyClassification delinquencyStage) {}

  public record NoPaymentResult(long statementBalancePaise, long lateFeePaise, long gstOnLateFeePaise,
      long overLimitPaise, long overlimitFeeImmediatePaise, List<Projection> projections, String verdict) {}

  /**
   * @param creditLimitPaise null when the card has no configured limit — then no over-limit fee applies
   * @param cycleLengthDays how often the over-limit fee recurs
   */
  public static NoPaymentResult simulate(LedgerTerms terms, List<LateFeeTier> lateFeeTiers,
      OverlimitFeeTerms overlimitFeeTerms, long statementBalancePaise, Long creditLimitPaise, int cycleLengthDays) {
    if (cycleLengthDays < 1) {
      throw new IllegalArgumentException("cycleLengthDays must be at least 1");
    }
    BigDecimal statementBalance = Money.rupees(statementBalancePaise);
    BigDecimal lateFee = Money.rupees(LateFeeAndDelinquencyCalculator.lateFee(statementBalancePaise, lateFeeTiers));
    BigDecimal gstOnLateFee = Money.gst(lateFee);

    BigDecimal overLimit = BigDecimal.ZERO;
    BigDecimal overlimitFeeOnce = BigDecimal.ZERO;
    if (creditLimitPaise != null) {
      var utilization = CreditUtilizationCalculator.utilization(statementBalancePaise, creditLimitPaise);
      overLimit = Money.rupees(utilization.overLimitPaise());
      if (overLimit.compareTo(BigDecimal.ZERO) > 0 && overlimitFeeTerms != null) {
        overlimitFeeOnce = CreditUtilizationCalculator.fee(overLimit, overlimitFeeTerms);
      }
    }
    BigDecimal overlimitFeeOnceGst = Money.gst(overlimitFeeOnce);
    BigDecimal retailApr = Money.percent(terms.aprBps());

    List<Projection> projections = new ArrayList<>(PROJECTION_DAYS.length);
    for (int daysOverdue : PROJECTION_DAYS) {
      List<Lot> openingLots =
          List.of(new Lot(SIMULATION_EPOCH, "retail_purchases", statementBalance, retailApr, null));
      LocalDate endDate = SIMULATION_EPOCH.plusDays(daysOverdue - 1L);
      BigDecimal interest =
          LedgerEngine.accrue(List.of(), terms, SIMULATION_EPOCH, endDate, openingLots, false).totalInterest();
      DelinquencyClassification stage = LateFeeAndDelinquencyCalculator.classifyDelinquencyStage(daysOverdue);

      // ceil(daysOverdue / cycleLengthDays) — integer arithmetic, both positive, so exactly the original's value
      int cyclesElapsed = (daysOverdue + cycleLengthDays - 1) / cycleLengthDays;
      BigDecimal overlimitFeeTotal = overLimit.compareTo(BigDecimal.ZERO) > 0
          ? Money.round2(overlimitFeeOnce.multiply(BigDecimal.valueOf(cyclesElapsed)))
          : BigDecimal.ZERO;
      BigDecimal overlimitFeeTotalGst = Money.gst(overlimitFeeTotal);

      BigDecimal totalOutstanding = Money.round2(statementBalance
          .add(interest).add(Money.gst(interest))
          .add(lateFee).add(gstOnLateFee)
          .add(overlimitFeeTotal).add(overlimitFeeTotalGst));

      projections.add(new Projection(daysOverdue, Money.paise(interest),
          Money.paise(Money.round2(overlimitFeeTotal.add(overlimitFeeTotalGst))), Money.paise(totalOutstanding),
          stage));
    }

    Projection at90 = projections.get(projections.size() - 1);
    String verdict = ("Missing payment costs ₹%s in fees immediately, plus accruing interest. By 90 days overdue, "
        + "the account is classified %s — %s.")
        .formatted(Money.rs(lateFee.add(gstOnLateFee).add(overlimitFeeOnce).add(overlimitFeeOnceGst)),
            at90.delinquencyStage().stage(), at90.delinquencyStage().label().toLowerCase(java.util.Locale.ROOT));

    return new NoPaymentResult(Money.paise(statementBalance), Money.paise(lateFee), Money.paise(gstOnLateFee),
        Money.paise(overLimit), Money.paise(Money.round2(overlimitFeeOnce.add(overlimitFeeOnceGst))), projections,
        verdict);
  }
}
