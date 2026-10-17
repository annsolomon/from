package in.dexter.identity.infra;

import in.dexter.identity.app.LoginThrottle;
import in.dexter.identity.domain.ThrottlePolicy;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link LoginThrottle} on Redis (MASTER_PLAN 7.1, ADR-0010). Per key three strings under the service prefix:
 * {@code :n} failures in the window, {@code :t} time of the last failure (ms), {@code :l} the lock (PX lockFor).
 * Every operation is one Lua script (atomic per key, only the ACL-allowed commands GET SET INCR PEXPIRE PTTL
 * UNLINK). The current time is an argument, so tests drive it with a fixed clock.
 */
public final class RedisLoginThrottle implements LoginThrottle {
  static final String PREFIX = "identity:thr:";

  // KEYS n, t, l; ARGV now, free, baseMs, maxMs. Returns {allowed(1/0), retryAfterMs}.
  static final RedisScript<List> CHECK = RedisScript.of("""
      local lock = redis.call('PTTL', KEYS[3])
      if lock > 0 then return {0, lock} end
      local n = tonumber(redis.call('GET', KEYS[1]) or '0')
      local free = tonumber(ARGV[2])
      if n <= free then return {1, 0} end
      local delay = tonumber(ARGV[3]) * (2 ^ (n - free))
      local max = tonumber(ARGV[4])
      if delay > max then delay = max end
      local last = tonumber(redis.call('GET', KEYS[2]) or '0')
      local wait = last + delay - tonumber(ARGV[1])
      if wait > 0 then return {0, math.floor(wait)} end
      return {1, 0}
      """, List.class);

  // KEYS n, t, l; ARGV now, windowMs, lockAfter, lockForMs. Returns {failures, lockedNow(1/0)}.
  static final RedisScript<List> FAIL = RedisScript.of("""
      local n = redis.call('INCR', KEYS[1])
      if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
      redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
      local locked = 0
      if n >= tonumber(ARGV[3]) and redis.call('PTTL', KEYS[3]) <= 0 then
        redis.call('SET', KEYS[3], '1', 'PX', ARGV[4])
        locked = 1
        if redis.call('PTTL', KEYS[1]) < tonumber(ARGV[4]) then redis.call('PEXPIRE', KEYS[1], ARGV[4]) end
      end
      return {n, locked}
      """, List.class);

  private final StringRedisTemplate redis;

  public RedisLoginThrottle(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public Verdict check(String key, ThrottlePolicy p, Instant now) {
    List<?> r = RedisCalls.call(() -> redis.execute(CHECK, keys(key), ms(now), String.valueOf(p.freeFailures()),
        String.valueOf(p.baseDelay().toMillis()), String.valueOf(p.maxDelay().toMillis())));
    return new Verdict(num(r, 0) == 1, num(r, 1));
  }

  @Override
  public Failure fail(String key, ThrottlePolicy p, Instant now) {
    List<?> r = RedisCalls.call(() -> redis.execute(FAIL, keys(key), ms(now), String.valueOf(p.window().toMillis()),
        String.valueOf(p.lockAfter()), String.valueOf(p.lockFor().toMillis())));
    return new Failure((int) num(r, 0), num(r, 1) == 1);
  }

  /** Clears the failure count; an active lock stays until it expires (a correct password does not lift it). */
  @Override
  public void reset(String key) {
    RedisCalls.call(() -> redis.unlink(List.of(PREFIX + key + ":n", PREFIX + key + ":t")));
  }

  static List<String> keys(String key) {
    return List.of(PREFIX + key + ":n", PREFIX + key + ":t", PREFIX + key + ":l");
  }

  private static String ms(Instant now) {
    return String.valueOf(now.toEpochMilli());
  }

  private static long num(List<?> r, int i) {
    return r == null || r.size() <= i ? 0 : ((Number) r.get(i)).longValue();
  }
}
