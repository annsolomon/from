package in.dexter.portfolio.calc;

import in.dexter.portfolio.calc.CustomFeeCalculator.CustomFee;
import in.dexter.portfolio.calc.CustomFeeCalculator.CustomFeeLine;
import java.math.BigDecimal;
import java.util.List;

/**
 * Pay the full statement: no interest, grace carries forward (old repo {@code calc/FullPaymentCalculator}, itself a
 * port of {@code simulators.js simulateFullPayment}).
 *
 * <p>The simplest of the four scenarios. The only cost that can still apply is a configured custom fee, which is why
 * it is not a one-liner even though the balance itself is free.
 */
public final class FullPaymentCalculator {

  private FullPaymentCalculator() {}

  public record FullPaymentResult(long paymentPaise, long customFeesPaise, long gstOnCustomFeesPaise,
      List<CustomFeeLine> customFeesBreakdown, long totalCostPaise, boolean gracePreservedNextCycle, String verdict) {}

  public static FullPaymentResult simulate(long statementBalancePaise, List<CustomFee> customFees) {
    BigDecimal balance = Money.rupees(statementBalancePaise);
    CustomFeeCalculator.Cycle fees = CustomFeeCalculator.cycle(customFees, balance, 1);
    BigDecimal totalCost = Money.round2(balance.add(fees.total()).add(fees.gst()));

    String verdict = fees.total().compareTo(BigDecimal.ZERO) > 0
        ? "Balance itself costs nothing extra — but ₹%s in configured custom fees still applies."
            .formatted(Money.rs(fees.total().add(fees.gst())))
        : "Zero extra cost — grace period carries into next cycle.";

    return new FullPaymentResult(Money.paise(balance), Money.paise(fees.total()), Money.paise(fees.gst()),
        fees.breakdown(), Money.paise(totalCost), true, verdict);
  }
}
