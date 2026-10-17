package in.dexter.identity.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * Login throttling (MASTER_PLAN 7.1 "progressive delay, then a 15-minute lock after 10 failures"). Within
 * {@code window}: the first {@code freeFailures} failures cost nothing; after that each attempt must wait
 * {@code baseDelay · 2^(failures − freeFailures)} (capped at {@code maxDelay}) since the last failure; at
 * {@code lockAfter} failures the key is locked for {@code lockFor}. A success resets it. Keys are an email hash, an
 * account/admin id or an IP — never revealing whether an email exists.
 */
public record ThrottlePolicy(int freeFailures, Duration baseDelay, Duration maxDelay, int lockAfter, Duration lockFor,
    Duration window) {
  public ThrottlePolicy {
    Objects.requireNonNull(baseDelay, "baseDelay");
    Objects.requireNonNull(maxDelay, "maxDelay");
    Objects.requireNonNull(lockFor, "lockFor");
    Objects.requireNonNull(window, "window");
    if (freeFailures < 0 || lockAfter < 1 || freeFailures > lockAfter) {
      throw new IllegalArgumentException("0 <= freeFailures <= lockAfter, lockAfter >= 1");
    }
  }

  /** Defaults: 3 free, 1 s doubling up to 60 s, locked 15 min after 10 failures in 15 min. */
  public static ThrottlePolicy login() {
    return new ThrottlePolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(60), 10, Duration.ofMinutes(15),
        Duration.ofMinutes(15));
  }

  /** Per-IP: no delays, locked for the window after {@code limit} failures (credential stuffing across emails). */
  public static ThrottlePolicy perIp(int limit, Duration window) {
    return new ThrottlePolicy(limit, Duration.ZERO, Duration.ZERO, limit, window, window);
  }
}
