package in.dexter.identity.app;

import in.dexter.identity.domain.ThrottlePolicy;
import java.time.Instant;

/** Failure counters with progressive delay and lockout (infra: Redis Lua, atomic per key). */
public interface LoginThrottle {

  /** {@code retryAfterMillis} &gt; 0 when the attempt must be refused now. */
  record Verdict(boolean allowed, long retryAfterMillis) {}

  /** {@code lockedNow}: this failure crossed {@code lockAfter}. */
  record Failure(int failures, boolean lockedNow) {}

  Verdict check(String key, ThrottlePolicy policy, Instant now);

  Failure fail(String key, ThrottlePolicy policy, Instant now);

  void reset(String key);
}
