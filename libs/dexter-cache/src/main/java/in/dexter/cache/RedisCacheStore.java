package in.dexter.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Every Redis call the library makes. Reads, writes, locks and bumps are Lua scripts (one round trip each,
 * run with EVALSHA, EVAL on a cache miss), so the per-service ACL needs only {@code +eval +evalsha} plus the
 * commands the scripts call ({@code get set incr pexpire pttl unlink}), and {@code scan unlink} for the purge.
 * Any exception is a Redis failure; callers decide whether that means bypass (reads) or retry (Path B).
 */
public final class RedisCacheStore {
  /** KEYS[1] gen key; ARGV[1] value-key prefix, ARGV[2] params hash → {gen} or {gen, value, pttl}. */
  static final String READ_LUA = """
      local g = redis.call('GET', KEYS[1]) or '0'
      local k = ARGV[1] .. ':g' .. g .. ':' .. ARGV[2]
      local v = redis.call('GET', k)
      if not v then return {g} end
      return {g, v, tostring(redis.call('PTTL', k))}
      """;
  static final String WRITE_LUA = """
      redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
      return 1
      """;
  static final String LOCK_LUA = """
      if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 1 end
      return 0
      """;
  static final String UNLOCK_LUA = """
      if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('UNLINK', KEYS[1]) end
      return 0
      """;
  static final String BUMP_LUA = """
      for i = 1, #KEYS do
        redis.call('INCR', KEYS[i])
        redis.call('PEXPIRE', KEYS[i], ARGV[1])
      end
      return #KEYS
      """;

  @SuppressWarnings("rawtypes")
  private static final RedisScript<List> READ = new DefaultRedisScript<>(READ_LUA, List.class);
  private static final RedisScript<Long> WRITE = new DefaultRedisScript<>(WRITE_LUA, Long.class);
  private static final RedisScript<Long> LOCK = new DefaultRedisScript<>(LOCK_LUA, Long.class);
  private static final RedisScript<Long> UNLOCK = new DefaultRedisScript<>(UNLOCK_LUA, Long.class);
  private static final RedisScript<Long> BUMP = new DefaultRedisScript<>(BUMP_LUA, Long.class);

  private final StringRedisTemplate redis;

  public RedisCacheStore(StringRedisTemplate redis) {
    this.redis = Objects.requireNonNull(redis, "redis");
  }

  /** The generation, the raw value under it (null on a miss) and its remaining TTL in ms (-1 on a miss). */
  record Read(long gen, @Nullable String value, long pttlMillis) {}

  Read read(String genKey, String valuePrefix, String paramsHash) {
    List<?> r = redis.execute(READ, List.of(genKey), valuePrefix, paramsHash);
    if (r == null || r.isEmpty()) {
      throw new IllegalStateException("cache read script returned nothing");
    }
    long gen = Long.parseLong(String.valueOf(r.get(0)));
    if (r.size() < 3) {
      return new Read(gen, null, -1);
    }
    return new Read(gen, String.valueOf(r.get(1)), Long.parseLong(String.valueOf(r.get(2))));
  }

  void write(String valueKey, String value, Duration ttl) {
    redis.execute(WRITE, List.of(valueKey), value, Long.toString(Math.max(1, ttl.toMillis())));
  }

  boolean tryLock(String lockKey, String token, Duration ttl) {
    Long r = redis.execute(LOCK, List.of(lockKey), token, Long.toString(ttl.toMillis()));
    return r != null && r == 1L;
  }

  void unlock(String lockKey, String token) {
    redis.execute(UNLOCK, List.of(lockKey), token);
  }

  /** INCR + PEXPIRE on every gen key, one round trip. All keys of one account share a slot (hash tag). */
  void bump(List<String> genKeys, Duration genTtl) {
    if (genKeys.isEmpty()) {
      return;
    }
    redis.execute(BUMP, genKeys, Long.toString(genTtl.toMillis()));
  }

  /** SCAN MATCH each pattern (COUNT 500) and UNLINK in batches. Returns the number of keys removed. */
  long unlinkMatching(String... patterns) {
    long removed = 0;
    for (String pattern : patterns) {
      List<String> batch = new ArrayList<>();
      try (Cursor<String> c = redis.scan(ScanOptions.scanOptions().match(pattern).count(500).build())) {
        while (c.hasNext()) {
          batch.add(c.next());
          if (batch.size() == 500) {
            removed += unlink(batch);
            batch.clear();
          }
        }
      }
      removed += unlink(batch);
    }
    return removed;
  }

  private long unlink(List<String> keys) {
    if (keys.isEmpty()) {
      return 0;
    }
    Long n = redis.unlink(keys);
    return n == null ? 0 : n;
  }
}
