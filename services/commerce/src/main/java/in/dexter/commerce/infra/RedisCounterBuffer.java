package in.dexter.commerce.infra;

import in.dexter.commerce.app.CounterBuffer;
import in.dexter.commerce.domain.Hashing;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The Redis side of the counters (MASTER_PLAN 8.3), inside the service's ACL (keys {@code commerce:*}; commands
 * get/set/incr/pexpire/unlink/scan/eval only — every compound step is a Lua script of those, so each is atomic):
 * <ul>
 *   <li>{@code commerce:cnt:<metric>:<yyyy-mm-dd>:<id>} — pending increments; the flusher drains each key with a
 *       get-and-unlink script, so an increment lands in exactly one drain.</li>
 *   <li>{@code commerce:uq:<day>:<deal>:<visitor>} — SET NX (48 h) marks a visitor's first click on a deal that IST
 *       day; the unique counter is incremented only when the mark was new (same script as the click).</li>
 *   <li>{@code commerce:fc:<campaign>:<visitor>} — the frequency-cap window: checked and incremented in one
 *       script, its TTL the campaign's window from the first impression.</li>
 * </ul>
 */
public final class RedisCounterBuffer implements CounterBuffer {
  static final String PREFIX = "commerce:cnt:";
  private static final long UNIQUE_TTL_MS = Duration.ofHours(48).toMillis();

  static final RedisScript<Long> CLICK = new DefaultRedisScript<>(
      "redis.call('incr', KEYS[1])\n"
          + "if redis.call('set', KEYS[3], '1', 'NX', 'PX', ARGV[1]) then\n"
          + "  redis.call('incr', KEYS[2])\n"
          + "  return 1\n"
          + "end\n"
          + "return 0", Long.class);
  static final RedisScript<Long> CAPPED_IMPRESSION = new DefaultRedisScript<>(
      "local n = tonumber(redis.call('get', KEYS[1]) or '0')\n"
          + "if n >= tonumber(ARGV[1]) then return 0 end\n"
          + "n = redis.call('incr', KEYS[1])\n"
          + "if n == 1 then redis.call('pexpire', KEYS[1], ARGV[2]) end\n"
          + "redis.call('incr', KEYS[2])\n"
          + "return n", Long.class);
  static final RedisScript<Long> DRAIN = new DefaultRedisScript<>(
      "local v = redis.call('get', KEYS[1])\n"
          + "if v then redis.call('unlink', KEYS[1]) return tonumber(v) end\n"
          + "return 0", Long.class);
  static final RedisScript<Long> ADD = new DefaultRedisScript<>(
      "local n = tonumber(redis.call('get', KEYS[1]) or '0') + tonumber(ARGV[1])\n"
          + "redis.call('set', KEYS[1], tostring(n))\n"
          + "return n", Long.class);

  private final StringRedisTemplate redis;

  public RedisCounterBuffer(StringRedisTemplate redis) {
    this.redis = redis;
  }

  static String key(Metric m, LocalDate day, UUID id) {
    return PREFIX + m.code() + ":" + day + ":" + id;
  }

  @Override
  public void dealClick(UUID dealId, LocalDate day, byte[] visitorHash) {
    redis.execute(CLICK, List.of(key(Metric.DEAL_CLICKS, day, dealId), key(Metric.DEAL_UNIQUES, day, dealId),
        "commerce:uq:" + day + ":" + dealId + ":" + Hashing.hex(visitorHash)), Long.toString(UNIQUE_TTL_MS));
  }

  @Override
  public void adClick(UUID creativeId, LocalDate day) {
    redis.opsForValue().increment(key(Metric.AD_CLICKS, day, creativeId));
  }

  @Override
  public boolean impression(UUID campaignId, UUID creativeId, LocalDate day, byte[] visitorHash, int cap,
      Duration window) {
    Long n = redis.execute(CAPPED_IMPRESSION, List.of("commerce:fc:" + campaignId + ":" + Hashing.hex(visitorHash),
        key(Metric.AD_IMPRESSIONS, day, creativeId)), Integer.toString(cap), Long.toString(window.toMillis()));
    return n != null && n > 0;
  }

  @Override
  public List<Delta> drain(int maxKeys) {
    List<String> keys = redis.execute((RedisCallback<List<String>>) c -> {
      List<String> found = new ArrayList<>();
      ScanOptions opts = ScanOptions.scanOptions().match(PREFIX + "*").count(Math.max(100, maxKeys)).build();
      try (Cursor<byte[]> cur = c.keyCommands().scan(opts)) {
        while (cur.hasNext() && found.size() < maxKeys) {
          found.add(new String(cur.next(), StandardCharsets.UTF_8));
        }
      }
      return found;
    });
    List<Delta> out = new ArrayList<>();
    if (keys == null) {
      return out;
    }
    for (String k : keys) {
      Long v = redis.execute(DRAIN, List.of(k));
      if (v != null && v > 0) {
        out.add(parse(k, v));
      }
    }
    return out;
  }

  @Override
  public void restore(List<Delta> deltas) {
    for (Delta d : deltas) {
      redis.execute(ADD, List.of(key(d.metric(), d.day(), d.id())), Long.toString(d.count()));
    }
  }

  static Delta parse(String key, long count) {
    String[] p = key.substring(PREFIX.length()).split(":");
    return new Delta(Metric.ofCode(p[0]), LocalDate.parse(p[1]), UUID.fromString(p[2]), count);
  }
}
