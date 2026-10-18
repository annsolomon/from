package in.dexter.catalog.app;

import in.dexter.core.time.Clocks;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.springframework.scheduling.support.CronExpression;

/**
 * Source schedules: standard five-field cron ({@code minute hour day month weekday}) in IST, at most hourly (the
 * per-host politeness of MASTER_PLAN P10 starts with not crawling an issuer every minute).
 */
final class Schedules {
  static final Duration MIN_INTERVAL = Duration.ofHours(1);

  private Schedules() {}

  /** Empty when the expression is not a valid five-field cron or fires more often than hourly. */
  static Optional<CronExpression> parse(String expr) {
    if (expr == null || expr.isBlank() || expr.strip().split("\\s+").length != 5) {
      return Optional.empty();
    }
    try {
      CronExpression c = CronExpression.parse("0 " + expr.strip());
      ZonedDateTime t0 = ZonedDateTime.now(Clocks.DEFAULT_ZONE);
      // check a day of consecutive firings: none closer than the minimum interval
      ZonedDateTime a = c.next(t0);
      for (int i = 0; a != null && i < 48; i++) {
        ZonedDateTime b = c.next(a);
        if (b == null) {
          break;
        }
        if (Duration.between(a, b).compareTo(MIN_INTERVAL) < 0) {
          return Optional.empty();
        }
        a = b;
      }
      return Optional.of(c);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** The next firing after {@code now} (IST), or null. */
  static Instant next(String expr, Instant now) {
    return parse(expr).map(c -> c.next(now.atZone(Clocks.DEFAULT_ZONE))).map(ZonedDateTime::toInstant).orElse(null);
  }
}
