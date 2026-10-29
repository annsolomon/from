package in.dexter.portfolio.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The four minimum-due formulas Indian issuers use (old repo {@code calc/MinimumDueCalculator}, itself a port of the
 * Node calculation-engine's {@code minimumDue.js}). Same formula types, same branching, same rounding.
 *
 * <p>The optional {@link MinimumDueTerms} fields are the ones a given formula does not read — the original read only
 * what each case needed and would have failed on a missing one. This port rejects a missing required field up front
 * with {@link IllegalArgumentException} instead (the service maps it to a 400); no computed value changes.
 */
public final class MinimumDueCalculator {

  /** Intermediate precision before the final {@code round2}, as the original had it. */
  private static final int CALC_SCALE = 10;

  private MinimumDueCalculator() {}

  public enum FormulaType {
    /** A fixed amount, whatever is outstanding. */
    FLAT,
    /** A percentage of the outstanding balance, floored at a flat amount. */
    PERCENT_OF_OUTSTANDING,
    /** The higher of a percentage of the outstanding balance and a flat amount. */
    HIGHER_OF,
    /** The blended retail+finance-charge formula: the percentage covers the finance charge, or it is added on top. */
    FINANCE_CHARGE_CONDITIONAL
  }

  /** Not every field applies to every formula — see the static factories. */
  public record MinimumDueTerms(FormulaType formulaType, Long flatAmountPaise, Integer percentBps,
      Long flatFloorPaise, Integer retailCashPercentBps, Long roundUpToNearestPaise) {

    public MinimumDueTerms {
      if (formulaType == null) {
        throw new IllegalArgumentException("formulaType is required");
      }
      switch (formulaType) {
        case FLAT -> require(flatAmountPaise != null, "flatAmount");
        case PERCENT_OF_OUTSTANDING -> require(percentBps != null && flatFloorPaise != null, "percent and flatFloor");
        case HIGHER_OF -> require(percentBps != null && flatAmountPaise != null, "percent and flatAmount");
        case FINANCE_CHARGE_CONDITIONAL ->
            require(flatFloorPaise != null && retailCashPercentBps != null, "flatFloor and retailCashPercent");
      }
    }

    private static void require(boolean ok, String what) {
      if (!ok) {
        throw new IllegalArgumentException("minimum-due terms need " + what + " for this formula type");
      }
    }

    public static MinimumDueTerms flat(long flatAmountPaise) {
      return new MinimumDueTerms(FormulaType.FLAT, flatAmountPaise, null, null, null, null);
    }

    public static MinimumDueTerms percentOfOutstanding(int percentBps, long flatFloorPaise) {
      return new MinimumDueTerms(FormulaType.PERCENT_OF_OUTSTANDING, null, percentBps, flatFloorPaise, null, null);
    }

    public static MinimumDueTerms higherOf(int percentBps, long flatAmountPaise) {
      return new MinimumDueTerms(FormulaType.HIGHER_OF, flatAmountPaise, percentBps, null, null, null);
    }

    public static MinimumDueTerms financeChargeConditional(long flatFloorPaise, int retailCashPercentBps,
        long roundUpToNearestPaise) {
      return new MinimumDueTerms(FormulaType.FINANCE_CHARGE_CONDITIONAL, null, null, flatFloorPaise,
          retailCashPercentBps, roundUpToNearestPaise);
    }
  }

  /** Everything a statement can owe. All components default to zero, as the original's destructuring did. */
  public record MinimumDueInputs(long retailCashOutstandingPaise, long financeChargesPaise, long emiAmountPaise,
      long feesAndChargesPaise, long gstAmountPaise, long overlimitAmountPaise, long totalAmountDuePaise) {

    /** The common case: nothing owed but the balance itself. */
    public static MinimumDueInputs of(long retailCashOutstandingPaise) {
      return new MinimumDueInputs(retailCashOutstandingPaise, 0, 0, 0, 0, 0, 0);
    }
  }

  /** @return the minimum due, in paise */
  public static long calculate(MinimumDueTerms terms, MinimumDueInputs inputs) {
    return Money.paise(due(terms, inputs));
  }

  static BigDecimal due(MinimumDueTerms terms, MinimumDueInputs inputs) {
    BigDecimal outstanding = Money.rupees(inputs.retailCashOutstandingPaise());
    BigDecimal financeCharges = Money.rupees(inputs.financeChargesPaise());
    return switch (terms.formulaType()) {
      case FLAT -> Money.round2(Money.rupees(terms.flatAmountPaise()));

      case PERCENT_OF_OUTSTANDING -> {
        BigDecimal pct = percentOf(outstanding, terms.percentBps());
        yield Money.round2(pct.max(Money.rupees(terms.flatFloorPaise())));
      }

      case HIGHER_OF -> {
        BigDecimal pct = percentOf(outstanding, terms.percentBps());
        yield Money.round2(pct.max(Money.rupees(terms.flatAmountPaise())));
      }

      case FINANCE_CHARGE_CONDITIONAL -> {
        BigDecimal totalDue = Money.rupees(inputs.totalAmountDuePaise());
        BigDecimal floor = Money.rupees(terms.flatFloorPaise());
        if (totalDue.compareTo(BigDecimal.ZERO) > 0 && totalDue.compareTo(floor) <= 0) {
          // Small statements are simply paid off in full rather than minimum-dued.
          yield Money.round2(totalDue);
        }
        BigDecimal rate = Money.percent(terms.retailCashPercentBps())
            .divide(Money.HUNDRED, CALC_SCALE, RoundingMode.HALF_UP);
        BigDecimal nearest = Money.rupees(terms.roundUpToNearestPaise());
        BigDecimal blended = roundUpToNearest(rate.multiply(outstanding.add(financeCharges)), nearest);

        BigDecimal computed;
        if (blended.compareTo(financeCharges) > 0) {
          computed = Money.rupees(inputs.gstAmountPaise()).add(Money.rupees(inputs.emiAmountPaise()))
              .add(Money.rupees(inputs.feesAndChargesPaise())).add(blended);
        } else {
          BigDecimal retailOnly = roundUpToNearest(rate.multiply(outstanding), nearest);
          computed = Money.rupees(inputs.gstAmountPaise()).add(Money.rupees(inputs.emiAmountPaise()))
              .add(Money.rupees(inputs.feesAndChargesPaise())).add(financeCharges).add(retailOnly);
        }
        yield Money.round2(computed.max(floor).max(Money.rupees(inputs.overlimitAmountPaise())));
      }
    };
  }

  private static BigDecimal percentOf(BigDecimal amount, int percentBps) {
    return amount.multiply(Money.percent(percentBps)).divide(Money.HUNDRED, CALC_SCALE, RoundingMode.HALF_UP);
  }

  /** {@code ceil(value / nearest) * nearest} — issuers quote the minimum due rounded up to ₹10 or ₹100. */
  public static long roundUpToNearestPaise(long valuePaise, long nearestPaise) {
    return Money.paise(roundUpToNearest(Money.rupees(valuePaise), Money.rupees(nearestPaise)));
  }

  static BigDecimal roundUpToNearest(BigDecimal value, BigDecimal nearest) {
    if (nearest == null || nearest.compareTo(BigDecimal.ZERO) == 0) {
      return Money.round2(value);
    }
    return value.divide(nearest, 0, RoundingMode.CEILING).multiply(nearest);
  }
}
