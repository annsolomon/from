package in.dexter.identity.infra;

import in.dexter.identity.app.MfaChallenges;
import in.dexter.identity.domain.SecretTokens;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.json.JsonMapper;

/**
 * MFA challenges in Redis: {@code identity:mfa:<sha256(token)>} → JSON (PX ttl), wrong-code counter at {@code :f}.
 * The raw token exists only at the client. {@link #consume} is GET + UNLINK in one script: exactly one caller wins.
 */
public final class RedisMfaChallenges implements MfaChallenges {
  static final String PREFIX = "identity:mfa:";

  static final RedisScript<String> CONSUME = RedisScript.of("""
      local v = redis.call('GET', KEYS[1])
      if v then redis.call('UNLINK', KEYS[1], KEYS[2]) end
      return v
      """, String.class);

  // KEYS challenge, counter; ARGV ttlMs. 0 when the challenge is gone.
  static final RedisScript<Long> FAIL = RedisScript.of("""
      if redis.call('PTTL', KEYS[1]) <= 0 then return 0 end
      local n = redis.call('INCR', KEYS[2])
      if n == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[1]) end
      return n
      """, Long.class);

  private final StringRedisTemplate redis;
  private final JsonMapper json;

  public RedisMfaChallenges(StringRedisTemplate redis, JsonMapper json) {
    this.redis = redis;
    this.json = json;
  }

  @Override
  public String issue(Challenge c, Duration ttl) {
    String token = SecretTokens.newToken();
    String value = json.writeValueAsString(c);
    RedisCalls.call(() -> {
      redis.opsForValue().set(key(token), value, ttl);
      return null;
    });
    return token;
  }

  @Override
  public Optional<Challenge> peek(String token) {
    if (!SecretTokens.looksValid(token)) {
      return Optional.empty();
    }
    return parse(RedisCalls.call(() -> redis.opsForValue().get(key(token))));
  }

  @Override
  public Optional<Challenge> consume(String token) {
    if (!SecretTokens.looksValid(token)) {
      return Optional.empty();
    }
    String k = key(token);
    return parse(RedisCalls.call(() -> redis.execute(CONSUME, List.of(k, k + ":f"))));
  }

  @Override
  public int fail(String token, Duration ttl) {
    if (!SecretTokens.looksValid(token)) {
      return Integer.MAX_VALUE;
    }
    String k = key(token);
    Long n = RedisCalls.call(() -> redis.execute(FAIL, List.of(k, k + ":f"), String.valueOf(ttl.toMillis())));
    return n == null || n == 0 ? Integer.MAX_VALUE : n.intValue();
  }

  private Optional<Challenge> parse(String v) {
    return v == null ? Optional.empty() : Optional.of(json.readValue(v, Challenge.class));
  }

  static String key(String token) {
    return PREFIX + HexFormat.of().formatHex(SecretTokens.sha256(token.getBytes(StandardCharsets.UTF_8)));
  }
}
