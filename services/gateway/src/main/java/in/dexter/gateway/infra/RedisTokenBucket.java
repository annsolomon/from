package in.dexter.gateway.infra;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Mono;

/**
 * Token bucket in Redis (MASTER_PLAN 7.3): one Lua script per request, atomic per key. State is one string
 * {@code "<tokens>:<lastMillis>"} under {@code gateway:rl:*} (the gateway's ACL allows only GET/SET-family commands
 * and scripts). Capacity = the per-minute limit, refilled continuously; the current time is an argument (one clock
 * per gateway instance, testable without sleeping).
 */
public final class RedisTokenBucket {
  static final String PREFIX = "gateway:rl:";

  /** {@code remaining} whole tokens left; {@code retryAfterMillis} until the next token when refused. */
  public record Result(boolean allowed, long remaining, long retryAfterMillis, long fullInMillis) {}

  // KEYS bucket; ARGV capacity, tokensPerMs, nowMs, ttlMs → {allowed, remaining, retryAfterMs, fullInMs}
  static final RedisScript<List> TAKE = RedisScript.of("""
      local cap = tonumber(ARGV[1])
      local rate = tonumber(ARGV[2])
      local now = tonumber(ARGV[3])
      local tokens = cap
      local ts = now
      local v = redis.call('GET', KEYS[1])
      if v then
        local i = string.find(v, ':', 1, true)
        tokens = tonumber(string.sub(v, 1, i - 1))
        ts = tonumber(string.sub(v, i + 1))
      end
      if now > ts then tokens = math.min(cap, tokens + (now - ts) * rate) end
      local allowed = 0
      local retry = 0
      if tokens >= 1 then
        tokens = tokens - 1
        allowed = 1
      else
        retry = math.ceil((1 - tokens) / rate)
      end
      local stamp = now
      if ts > now then stamp = ts end
      redis.call('SET', KEYS[1], string.format('%.6f', tokens) .. ':' .. string.format('%d', stamp), 'PX', ARGV[4])
      return {allowed, math.floor(tokens), retry, math.ceil((cap - tokens) / rate)}
      """, List.class);

  private final ReactiveStringRedisTemplate redis;
  private final Duration timeout;

  public RedisTokenBucket(ReactiveStringRedisTemplate redis, Duration timeout) {
    this.redis = redis;
    this.timeout = timeout;
  }

  /** Takes one token from {@code key}'s bucket of {@code perMinute}. Errors (Redis down, timeout) propagate. */
  public Mono<Result> take(String key, int perMinute, long nowMillis) {
    double rate = perMinute / 60_000.0;
    String ttl = String.valueOf(Math.max(60_000L, (long) Math.ceil(perMinute / rate)) + 1_000);
    return redis.execute(TAKE, List.of(PREFIX + key), List.of(String.valueOf(perMinute),
            String.format(java.util.Locale.ROOT, "%.9f", rate), String.valueOf(nowMillis), ttl))
        .next()
        .timeout(timeout)
        .map(r -> new Result(num(r, 0) == 1, num(r, 1), num(r, 2), num(r, 3)));
  }

  private static long num(List<?> r, int i) {
    return r.size() > i && r.get(i) instanceof Number n ? n.longValue() : 0;
  }
}
