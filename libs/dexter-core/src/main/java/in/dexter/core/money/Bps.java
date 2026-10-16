package in.dexter.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A rate in basis points (MASTER_PLAN 3.4): 1% = 100 bps, APR 42% = 4200 bps. Stored as DB
 * {@code integer} ({@code rate_bps}). Range: -1,000,000..1,000,000 (±10,000%).
 */
public record Bps(int value) implements Comparable<Bps> {
  public static final int MAX = 1_000_000;
  public static final Bps ZERO = new Bps(0);
  public static final Bps ONE_PERCENT = new Bps(100);
  public static final Bps HUNDRED_PERCENT = new Bps(10_000);
  static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);

  public Bps {
    if (value < -MAX || value > MAX) {
      throw new IllegalArgumentException("bps out of range: " + value);
    }
  }

  public static Bps of(int value) {
    return new Bps(value);
  }

  /** Parses a percent string exactly ("1.5" → 150 bps). More than 2 decimals is rejected. */
  public static Bps ofPercent(String percent) {
    BigDecimal bps = new BigDecimal(percent.trim()).movePointRight(2);
    try {
      return new Bps(bps.intValueExact());
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("percent has more than 2 decimals or is out of range: " + percent);
    }
  }

  /** The rate as an exact fraction (150 bps → 0.015). */
  public BigDecimal asFraction() {
    return BigDecimal.valueOf(value).divide(TEN_THOUSAND);
  }

  /** Applies this rate to an amount of paise, rounding HALF_EVEN. */
  public long applyTo(long paise) {
    return applyTo(paise, RoundingMode.HALF_EVEN);
  }

  public long applyTo(long paise, RoundingMode rounding) {
    return BigDecimal.valueOf(paise).multiply(BigDecimal.valueOf(value))
        .divide(TEN_THOUSAND, 0, rounding).longValueExact();
  }

  public Bps plus(Bps other) {
    return new Bps(Math.addExact(value, other.value));
  }

  @Override
  public int compareTo(Bps o) {
    return Integer.compare(value, o.value);
  }

  @Override
  public String toString() {
    return asFraction().movePointRight(2).stripTrailingZeros().toPlainString() + "% (" + value + " bps)";
  }
}
