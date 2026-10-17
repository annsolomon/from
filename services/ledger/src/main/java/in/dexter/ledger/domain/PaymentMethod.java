package in.dexter.ledger.domain;

import java.util.Locale;

/** {@code expenses.payment_method}. Only {@code card} may carry a user card id. */
public enum PaymentMethod {
  CARD, UPI, CASH, NETBANKING, WALLET, OTHER;

  public String db() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Null when {@code s} is not a payment method. */
  public static PaymentMethod parse(String s) {
    if (s == null) {
      return null;
    }
    for (PaymentMethod m : values()) {
      if (m.db().equals(s)) {
        return m;
      }
    }
    return null;
  }
}
