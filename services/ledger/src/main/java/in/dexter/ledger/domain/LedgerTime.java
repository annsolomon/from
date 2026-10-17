package in.dexter.ledger.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Daily totals bucket expenses by the local date in {@code Asia/Kolkata} (A-45: the product is India-only; one
 * fixed zone keeps a row's day stable no matter which session's time zone wrote it, and matches the seed).
 */
public final class LedgerTime {
  public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

  private LedgerTime() {}

  public static LocalDate day(Instant t) {
    return LocalDate.ofInstant(t, ZONE);
  }
}
