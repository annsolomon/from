package in.dexter.testkit.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.cache.CacheScope;
import in.dexter.cache.CacheSettings;
import in.dexter.cache.DexterCache;
import in.dexter.core.tenant.Tenant;
import in.dexter.testkit.containers.TestRedis;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The per-service ACL of {@code infra/redis/acl.tmpl} (ADR-0004 §8): enough for every dexter-cache operation,
 * nothing else. The library swallows read errors (bypass), so "enough" is proven by zero bypasses.
 */
class RedisAclIT {
  static final String SVC = "acltest";
  static StringRedisTemplate asService;

  @BeforeAll
  static void setUp() {
    TestRedis.applyServiceAcl(SVC, "acltest-pw");
    TestRedis.applyServiceAcl("othersvc", "other-pw");
    asService = new StringRedisTemplate(TestRedis.connectionFactory(TestRedis.host(), TestRedis.port(), SVC,
        "acltest-pw"));
  }

  @Test
  void theServiceUserCanRunEveryCacheOperation() {
    DexterCache c = DexterCache.create(asService, SVC, CacheSettings.defaults(), JsonMapper.builder().build());
    CacheScope scope = CacheScope.tenant("acl", Duration.ofMinutes(1));
    CacheScope global = CacheScope.global("acl-global", Duration.ofMinutes(1));
    Tenant t = Tenant.user(UUID.randomUUID());
    assertThat(c.tenant().get(t, scope, Map.of("a", 1), String.class, () -> "v1")).isEqualTo("v1");
    assertThat(c.tenant().get(t, scope, Map.of("a", 1), String.class, () -> "never")).isEqualTo("v1");
    assertThat(c.tenant().find(t, scope, Map.of("a", 2), String.class, Optional::empty)).isEmpty();
    c.invalidator().bump(t.accountId(), List.of(scope));
    assertThat(c.global().get(global, Map.of(), String.class, () -> "g")).isEqualTo("g");
    c.invalidator().bumpGlobal(List.of(global));
    assertThat(c.invalidator().purgeAccount(t.accountId())).isPositive();
    assertThat(asService.execute((RedisCallback<String>) x -> x.ping())).isEqualTo("PONG");
    assertThat(c.metrics().snapshot().bypass()).as("no NOPERM was swallowed").isZero();
    assertThat(c.metrics().snapshot().invalidationFailures()).isZero();
  }

  @Test
  void foreignKeysAndDangerousCommandsAreRefused() {
    assertRefused("GET", "gen:othersvc:global:rules");
    assertRefused("SET", "othersvc:v1:x", "1");
    assertRefused("GET", "unprefixed");
    assertRefused("KEYS", "*");
    assertRefused("FLUSHALL");
    assertRefused("FLUSHDB");
    assertRefused("CONFIG", "GET", "maxmemory");
    assertRefused("DEL", SVC + ":v1:x");
    assertRefused("ACL", "LIST");
    // a script runs with the caller's permissions: it cannot reach another service's keys either
    assertRefused("EVAL", "return redis.call('GET', KEYS[1])", "1", "othersvc:v1:x");
    assertRefused("EVAL", "return redis.call('GET', ARGV[1])", "0", "othersvc:v1:x");
  }

  static void assertRefused(String command, String... args) {
    byte[][] raw = new byte[args.length][];
    for (int i = 0; i < args.length; i++) {
      raw[i] = args[i].getBytes(StandardCharsets.UTF_8);
    }
    assertThatThrownBy(() -> asService.execute((RedisCallback<Object>) x -> x.execute(command, raw)))
        .as(command + " " + String.join(" ", args))
        .satisfies(e -> assertThat(messages(e)).containsAnyOf("NOPERM", "can't access", "no permissions"));
  }

  static String messages(Throwable e) {
    StringBuilder sb = new StringBuilder();
    for (Throwable t = e; t != null; t = t.getCause()) {
      sb.append(t.getMessage()).append(" | ");
    }
    return sb.toString();
  }
}
