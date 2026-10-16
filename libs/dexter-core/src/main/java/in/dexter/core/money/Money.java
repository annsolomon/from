package in.dexter.core.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An amount of money in minor units (paise for INR). Stored as DB {@code bigint amount_paise} plus
 * {@code currency char(3)} (MASTER_PLAN 3.4). All arithmetic is exact and overflow-checked; the only
 * rounding happens in {@link #times(Bps)} / {@link #timesFraction}, which use HALF_EVEN by default.
 * Never built from binary floating point.
 */
public record Money(long paise, String currency) implements Comparable<Money> {
  public static final String INR = "INR";
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

  public Money {
    Objects.requireNonNull(currency, "currency");
    if (!CURRENCY.matcher(currency).matches()) {
      throw new IllegalArgumentException("currency must be ISO-4217 alpha-3: " + currency);
    }
  }

  public static Money ofPaise(long paise) {
    return new Money(paise, INR);
  }

  public static Money zero() {
    return new Money(0, INR);
  }

  /** Parses a rupee amount exactly ("1234.5" → 123450 paise). More than 2 decimals is rejected. */
  public static Money ofRupees(String rupees) {
    BigDecimal p = new BigDecimal(rupees.trim()).movePointRight(2);
    try {
      return ofPaise(p.longValueExact());
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("more than 2 decimals or out of range: " + rupees);
    }
  }

  public Money plus(Money o) {
    same(o);
    return new Money(Math.addExact(paise, o.paise), currency);
  }

  public Money minus(Money o) {
    same(o);
    return new Money(Math.subtractExact(paise, o.paise), currency);
  }

  public Money negate() {
    return new Money(Math.negateExact(paise), currency);
  }

  public Money times(long quantity) {
    return new Money(Math.multiplyExact(paise, quantity), currency);
  }

  /** Applies a rate in bps with HALF_EVEN rounding (banker's rounding, no drift over many ops). */
  public Money times(Bps rate) {
    return times(rate, RoundingMode.HALF_EVEN);
  }

  public Money times(Bps rate, RoundingMode rounding) {
    return new Money(rate.applyTo(paise, rounding), currency);
  }

  /** Multiplies by an exact fraction (for example a pro-rata factor) with the given rounding. */
  public Money timesFraction(BigDecimal fraction, RoundingMode rounding) {
    return new Money(BigDecimal.valueOf(paise).multiply(fraction).setScale(0, rounding).longValueExact(), currency);
  }

  /**
   * Splits into {@code parts} amounts that sum exactly to this amount; the first {@code remainder}
   * parts get one extra paisa (for EMIs and pro-rata splits).
   */
  public Money[] allocate(int parts) {
    if (parts <= 0) {
      throw new IllegalArgumentException("parts must be > 0");
    }
    long base = paise / parts;
    long rem = paise % parts;
    Money[] out = new Money[parts];
    for (int i = 0; i < parts; i++) {
      long extra = i < Math.abs(rem) ? Long.signum(rem) : 0;
      out[i] = new Money(base + extra, currency);
    }
    return out;
  }

  public boolean isZero() {
    return paise == 0;
  }

  public boolean isPositive() {
    return paise > 0;
  }

  public boolean isNegative() {
    return paise < 0;
  }

  public Money max(Money o) {
    return compareTo(o) >= 0 ? this : o;
  }

  public Money min(Money o) {
    return compareTo(o) <= 0 ? this : o;
  }

  @Override
  public int compareTo(Money o) {
    same(o);
    return Long.compare(paise, o.paise);
  }

  /** Exact decimal major-unit value ("1234.50"). For display/logs only. */
  public BigDecimal toMajor() {
    return BigDecimal.valueOf(paise, 2);
  }

  /** Log/display form, for example {@code INR 1,234.50}. Never parse this back. */
  @Override
  public String toString() {
    String major = String.format(java.util.Locale.ROOT, "%,.2f", toMajor());
    return currency + " " + major;
  }

  private void same(Money o) {
    if (!currency.equals(o.currency)) {
      throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + o.currency);
    }
  }
}
