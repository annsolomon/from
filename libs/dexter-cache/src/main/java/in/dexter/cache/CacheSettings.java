package in.dexter.cache;

import java.time.Duration;
import java.util.Objects;

/**
 * Tuning (ADR-0004 §3–4). Defaults: lock 5 s, lock wait 150 ms, negative TTL 30 s, TTL jitter ±10 %,
 * breaker open 1 s after a Redis error, gen-key expiry 7 days, values above 256 KB are not cached,
 * XFetch beta 1.0.
 */
public record CacheSettings(Duration lockTtl, Duration lockWait, Duration negativeTtl, double jitter,
    Duration breakerOpen, Duration genTtl, int maxValueBytes, double earlyRefreshBeta) {

  public CacheSettings {
    Objects.requireNonNull(lockTtl, "lockTtl");
    Objects.requireNonNull(lockWait, "lockWait");
    Objects.requireNonNull(negativeTtl, "negativeTtl");
    Objects.requireNonNull(breakerOpen, "breakerOpen");
    Objects.requireNonNull(genTtl, "genTtl");
    if (jitter < 0 || jitter > 0.5) {
      throw new IllegalArgumentException("jitter must be within 0..0.5");
    }
    if (lockTtl.toMillis() < 100 || lockWait.isNegative() || negativeTtl.toMillis() < 1000 || breakerOpen.isNegative()
        || genTtl.compareTo(Duration.ofHours(2)) < 0 || maxValueBytes < 1024 || earlyRefreshBeta < 0) {
      throw new IllegalArgumentException("invalid cache settings: " + this);
    }
  }

  public static CacheSettings defaults() {
    return new CacheSettings(Duration.ofSeconds(5), Duration.ofMillis(150), Duration.ofSeconds(30), 0.10,
        Duration.ofSeconds(1), Duration.ofDays(7), 256 * 1024, 1.0);
  }

  public CacheSettings withEarlyRefreshBeta(double beta) {
    return new CacheSettings(lockTtl, lockWait, negativeTtl, jitter, breakerOpen, genTtl, maxValueBytes, beta);
  }

  public CacheSettings withBreakerOpen(Duration open) {
    return new CacheSettings(lockTtl, lockWait, negativeTtl, jitter, open, genTtl, maxValueBytes, earlyRefreshBeta);
  }

  public CacheSettings withLockWait(Duration wait) {
    return new CacheSettings(lockTtl, wait, negativeTtl, jitter, breakerOpen, genTtl, maxValueBytes, earlyRefreshBeta);
  }
}
