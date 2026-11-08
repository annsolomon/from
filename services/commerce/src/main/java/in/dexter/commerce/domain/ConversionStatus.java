package in.dexter.commerce.domain;

import java.util.Locale;

/** An affiliate conversion: reported pending, then approved or rejected by the network; decided is final. */
public enum ConversionStatus {
  PENDING, APPROVED, REJECTED;

  public String db() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static ConversionStatus ofDb(String s) {
    return valueOf(s.toUpperCase(Locale.ROOT));
  }

  /** A later postback may move pending forward; anything else (a replay, a regression) changes nothing. */
  public boolean mayMoveTo(ConversionStatus next) {
    return this == PENDING && next != PENDING;
  }
}
