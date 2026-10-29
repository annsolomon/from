package in.dexter.portfolio.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Spend against the card's credit limit, and the over-limit fee that follows (old repo
 * {@code calc/CreditUtilizationCalculator}, itself a port of {@code creditUtilizationEngine.js}).
 *
 * <p>The original's own note is worth keeping: the credit limit existed on the card record long before anything
 * compared spend against it, and the over-limit amount was hardcoded to zero elsewhere in the engine even though
 * every card's fee schedule already had an over-limit fee. This is that missing comparison. In v2 the equivalent
 * field is {@code portfolio.user_cards.credit_limit_paise}.
 */
public final class CreditUtilizationCalculator {

  /** Intermediate precision before the final {@code round2}, as the original had it. */
  private static final int CALC_SCALE = 10;

  private CreditUtilizationCalculator() {}

  /**
   * @param creditLimitPaise null when the card has no configured limit — then there is no utilization to report
   * @param utilizationBps spend ÷ limit, in basis points (12000 = 120 %); null when no limit is configured
   */
  public record UtilizationResult(boolean creditLimitConfigured, Long creditLimitPaise, long spentPaise,
      Long availableCreditPaise, Integer utilizationBps, boolean overLimit, long overLimitPaise) {}

  /** "The higher of x % of the over-limit amount, or a flat minimum" — the shape of the card's other fees. */
  public record OverlimitFeeTerms(int percentBps, long flatMinimumPaise) {}

  public static UtilizationResult utilization(long spentPaise, Long creditLimitPaise) {
    BigDecimal spent = Money.rupees(spentPaise);
    if (creditLimitPaise == null) {
      return new UtilizationResult(false, null, Money.paise(spent), null, null, false, 0L);
    }
    if (creditLimitPaise <= 0) {
      // The original would have divided by zero here. A configured limit of zero is a data error, not a card.
      throw new IllegalArgumentException("a configured credit limit must be greater than zero");
    }
    BigDecimal limit = Money.rupees(creditLimitPaise.longValue());
    BigDecimal overLimit = Money.round2(spent.subtract(limit).max(BigDecimal.ZERO));
    BigDecimal utilization = spent.divide(limit, CALC_SCALE, RoundingMode.HALF_UP).multiply(Money.HUNDRED);
    return new UtilizationResult(true, Money.paise(limit), Money.paise(spent),
        Money.paise(limit.subtract(spent).max(BigDecimal.ZERO)), Money.bps(utilization),
        overLimit.compareTo(BigDecimal.ZERO) > 0, Money.paise(overLimit));
  }

  public static long overlimitFee(long overLimitPaise, OverlimitFeeTerms terms) {
    return Money.paise(fee(Money.rupees(overLimitPaise), terms));
  }

  static BigDecimal fee(BigDecimal overLimit, OverlimitFeeTerms terms) {
    if (overLimit == null || overLimit.compareTo(BigDecimal.ZERO) <= 0 || terms == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal percentAmount = Money.round2(overLimit.multiply(Money.percent(terms.percentBps()))
        .divide(Money.HUNDRED, CALC_SCALE, RoundingMode.HALF_UP));
    return Money.round2(percentAmount.max(Money.rupees(terms.flatMinimumPaise())));
  }
}
