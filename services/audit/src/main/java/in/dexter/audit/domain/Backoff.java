package in.dexter.audit.domain;

import java.time.Duration;

/**
 * Notification retry (MASTER_PLAN P18): attempt n failed → wait base · 2^(n-1), capped; after {@code maxAttempts}
 * the notification is failed for good.
 */
public record Backoff(Duration base, Duration cap, int maxAttempts) {
  public Backoff {
    if (base.isNegative() || base.isZero() || cap.compareTo(base) < 0 || maxAttempts < 1) {
      throw new IllegalArgumentException("bad backoff");
    }
  }

  /** The wait after failed attempt number {@code attempt} (1-based). */
  public Duration after(int attempt) {
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt >= 1");
    }
    int shift = Math.min(attempt - 1, 30);
    long ms = base.toMillis() << shift;
    return ms <= 0 || ms > cap.toMillis() ? cap : Duration.ofMillis(ms);
  }

  /** True when attempt number {@code attempt} was the last one allowed. */
  public boolean exhausted(int attempt) {
    return attempt >= maxAttempts;
  }
}
