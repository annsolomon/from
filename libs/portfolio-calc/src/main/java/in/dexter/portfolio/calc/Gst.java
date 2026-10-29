package in.dexter.portfolio.calc;

/**
 * GST on interest and fees, at the one rate and the one rounding every engine here applies (18 %, HALF_UP to the
 * paisa). Exposed because callers outside this library charge GST on the same figures and must round it the same way.
 */
public final class Gst {

  private Gst() {}

  /** 18 % of {@code amountPaise}, rounded HALF_UP to the paisa. */
  public static long on(long amountPaise) {
    return Money.paise(Money.gst(Money.rupees(amountPaise)));
  }
}
