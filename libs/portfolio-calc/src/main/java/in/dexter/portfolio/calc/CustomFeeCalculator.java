package in.dexter.portfolio.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-card configured fees for one cycle (old repo {@code calc/CustomFeeCalculator}, itself a port of
 * {@code simulators.js computeCustomFeesForCycle}).
 *
 * <p>A fee is either flat or a percentage of the cycle's balance, and applies either once (cycle 1) or every cycle.
 * GST at 18 % applies to the total.
 */
public final class CustomFeeCalculator {

  /** Intermediate precision before the final {@code round2}, as the original had it. */
  private static final int CALC_SCALE = 10;

  private CustomFeeCalculator() {}

  public enum AppliesTo {
    /** Cycle 1 only — a joining or annual fee. */
    ONCE,
    /** Every cycle — a service fee. */
    EVERY_CYCLE
  }

  public enum AmountType { FLAT, PERCENT }

  /**
   * One configured fee. Exactly one of {@code flatPaise} / {@code percentBps} is set, matching {@code amountType};
   * use the factories.
   */
  public record CustomFee(String id, String name, AppliesTo appliesTo, AmountType amountType, Long flatPaise,
      Integer percentBps) {

    public CustomFee {
      if (appliesTo == null || amountType == null) {
        throw new IllegalArgumentException("appliesTo and amountType are required");
      }
      boolean ok = amountType == AmountType.FLAT ? flatPaise != null : percentBps != null;
      if (!ok) {
        throw new IllegalArgumentException("a " + amountType + " fee needs its amount");
      }
    }

    public static CustomFee flat(String id, String name, AppliesTo appliesTo, long flatPaise) {
      return new CustomFee(id, name, appliesTo, AmountType.FLAT, flatPaise, null);
    }

    public static CustomFee percent(String id, String name, AppliesTo appliesTo, int percentBps) {
      return new CustomFee(id, name, appliesTo, AmountType.PERCENT, null, percentBps);
    }
  }

  public record CustomFeeLine(String id, String name, long amountPaise) {}

  public record CustomFeesResult(long totalPaise, long gstPaise, List<CustomFeeLine> breakdown) {}

  public static CustomFeesResult computeForCycle(List<CustomFee> customFees, long balancePaise, int cycleNumber) {
    Cycle cycle = cycle(customFees, Money.rupees(balancePaise), cycleNumber);
    return new CustomFeesResult(Money.paise(cycle.total()), Money.paise(cycle.gst()), cycle.breakdown());
  }

  record Cycle(BigDecimal total, BigDecimal gst, List<CustomFeeLine> breakdown) {}

  static Cycle cycle(List<CustomFee> customFees, BigDecimal balance, int cycleNumber) {
    if (customFees == null || customFees.isEmpty()) {
      return new Cycle(BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }
    BigDecimal total = BigDecimal.ZERO;
    List<CustomFeeLine> breakdown = new ArrayList<>();
    for (CustomFee fee : customFees) {
      if (fee.appliesTo() == AppliesTo.ONCE && cycleNumber != 1) {
        continue;
      }
      BigDecimal amount = fee.amountType() == AmountType.FLAT
          ? Money.rupees(fee.flatPaise())
          : Money.round2(balance.multiply(Money.percent(fee.percentBps()))
              .divide(Money.HUNDRED, CALC_SCALE, RoundingMode.HALF_UP));
      total = Money.round2(total.add(amount));
      breakdown.add(new CustomFeeLine(fee.id(), fee.name(), Money.paise(amount)));
    }
    return new Cycle(total, Money.gst(total), breakdown);
  }
}
