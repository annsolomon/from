package in.dexter.billing.domain;

import java.util.Locale;

/**
 * A payment's state, which only ever moves forward: {@code pending -> succeeded | failed}, and a decided payment
 * stays decided. Every source of an outcome - the provider's synchronous answer, a webhook in any order, the
 * sweeper's lookup - goes through {@link #canMoveTo}, so "succeeded" arriving before "created" (the out-of-order case)
 * and a replayed "succeeded" are both no-ops rather than a regression or a second activation.
 */
public enum PaymentStatus {
  PENDING, SUCCEEDED, FAILED;

  public String db() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static PaymentStatus of(String db) {
    return valueOf(db.toUpperCase(Locale.ROOT));
  }

  public boolean decided() {
    return this != PENDING;
  }

  /** True only for pending -> a decision. Pending -> pending and anything after a decision change nothing. */
  public boolean canMoveTo(PaymentStatus next) {
    return this == PENDING && next != PENDING;
  }
}
