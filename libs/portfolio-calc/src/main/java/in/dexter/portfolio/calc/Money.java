package in.dexter.portfolio.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The paise ↔ rupees boundary of this library (MASTER_PLAN P12 step 1: "paise {@code long} at the boundaries;
 * BigDecimal internally with the same rounding the old verified code used").
 *
 * <p>The old engines (old repo {@code card-portfolio-service/.../calc/*}) computed in BigDecimal <em>rupees</em> and
 * rounded money with one operation only: {@code round2 = setScale(2, HALF_UP)}. This port keeps the arithmetic in
 * rupees for exactly that reason — on rupees, {@code round2} <em>is</em> "to the paisa", so every rounding point of
 * the verified original lands on the same value here, and the ported golden fixtures hold to the paisa (ADR-0018).
 * Converting the internals to paise instead would move every one of those rounding points two decimal places.
 *
 * <p>Both conversions are exact and total: a paisa is 0.01 rupees, so {@link #rupees(long)} never rounds, and
 * {@link #paise(BigDecimal)} rounds only what the original already rounded.
 */
final class Money {

  /** GST on interest and fees, as every old engine held it (18 %). */
  static final BigDecimal GST_RATE = new BigDecimal("0.18");

  static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private Money() {}

  /** Exact — a paisa is 0.01 rupees. */
  static BigDecimal rupees(long paise) {
    return BigDecimal.valueOf(paise, 2);
  }

  /** Nullable money (a card with no configured credit limit, an absent fee schedule). */
  static BigDecimal rupees(Long paise) {
    return paise == null ? null : rupees(paise.longValue());
  }

  /** The old {@code round2}: the only money rounding the verified engines did. */
  static BigDecimal round2(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  static long paise(BigDecimal rupees) {
    return round2(rupees).movePointRight(2).longValueExact();
  }

  static Long paiseOrNull(BigDecimal rupees) {
    return rupees == null ? null : paise(rupees);
  }

  /** bps → the percent number the old engines carried: 4200 bps is "42". Exact. */
  static BigDecimal percent(int bps) {
    return BigDecimal.valueOf(bps, 2);
  }

  /** A computed percentage back out as bps (1 bp = 0.01 %, so round2 on the percent is exact in bps). */
  static int bps(BigDecimal percent) {
    return round2(percent).movePointRight(2).intValueExact();
  }

  /** Rupees inside a verdict string: the old engines interpolated {@code round2(...)} directly. */
  static String rs(BigDecimal rupees) {
    return round2(rupees).toPlainString();
  }

  static BigDecimal gst(BigDecimal amount) {
    return round2(amount.multiply(GST_RATE));
  }
}
