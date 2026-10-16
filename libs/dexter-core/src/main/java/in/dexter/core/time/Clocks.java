package in.dexter.core.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Clock and day-bucketing helpers (MASTER_PLAN 3.4: UTC storage, account-timezone days). */
public final class Clocks {
  /** Default account timezone. */
  public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");

  private Clocks() {}

  public static Clock systemUtc() {
    return Clock.systemUTC();
  }

  /** The calendar day of an instant in the account's timezone. */
  public static LocalDate dayOf(Instant instant, ZoneId accountZone) {
    return instant.atZone(accountZone).toLocalDate();
  }
}
