package in.dexter.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.identity.app.LoginThrottle;
import in.dexter.identity.domain.ThrottlePolicy;
import in.dexter.testkit.containers.TestRedis;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The Lua throttle against real Redis as the restricted {@code identity} ACL user (proves the scripts need only
 * the allowed commands). Time is an argument, so delays and locks are tested without sleeping.
 */
class RedisLoginThrottleIT {
  static final ThrottlePolicy LOGIN = new ThrottlePolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(8), 6,
      Duration.ofMinutes(15), Duration.ofMinutes(15));
  static RedisLoginThrottle throttle;
  static StringRedisTemplate redis;

  @BeforeAll
  static void setUp() {
    TestRedis.applyServiceAcl("identity", "throttle-it-pw");
    redis = new StringRedisTemplate(TestRedis.connectionFactory(TestRedis.host(), TestRedis.port(), "identity",
        "throttle-it-pw"));
    throttle = new RedisLoginThrottle(redis);
  }

  static String key() {
    return "t:" + UUID.randomUUID();
  }

  @Test
  void freeFailuresThenProgressiveDelayThenLock() {
    String k = key();
    Instant t = Instant.parse("2026-10-17T10:00:00Z");
    for (int i = 1; i <= 3; i++) {
      assertThat(throttle.check(k, LOGIN, t).allowed()).as("free attempt " + i).isTrue();
      assertThat(throttle.fail(k, LOGIN, t)).isEqualTo(new LoginThrottle.Failure(i, false));
    }
    // 3 failures: still free
    assertThat(throttle.check(k, LOGIN, t).allowed()).isTrue();
    throttle.fail(k, LOGIN, t); // 4 → next attempt waits 1s·2^1
    LoginThrottle.Verdict v = throttle.check(k, LOGIN, t.plusMillis(500));
    assertThat(v.allowed()).isFalse();
    assertThat(v.retryAfterMillis()).isEqualTo(1500);
    assertThat(throttle.check(k, LOGIN, t.plusSeconds(2)).allowed()).isTrue();
    throttle.fail(k, LOGIN, t.plusSeconds(2)); // 5 → 4s
    assertThat(throttle.check(k, LOGIN, t.plusSeconds(5)).retryAfterMillis()).isEqualTo(1000);
    assertThat(throttle.check(k, LOGIN, t.plusSeconds(6)).allowed()).isTrue();
    LoginThrottle.Failure sixth = throttle.fail(k, LOGIN, t.plusSeconds(6));
    assertThat(sixth).isEqualTo(new LoginThrottle.Failure(6, true));
    LoginThrottle.Verdict locked = throttle.check(k, LOGIN, t.plusSeconds(60));
    assertThat(locked.allowed()).isFalse();
    assertThat(locked.retryAfterMillis()).isBetween(Duration.ofMinutes(14).toMillis(), Duration.ofMinutes(15).toMillis());
    assertThat(throttle.fail(k, LOGIN, t.plusSeconds(61)).lockedNow()).as("lockedNow only once").isFalse();
  }

  @Test
  void delayIsCapped() {
    String k = key();
    ThrottlePolicy p = new ThrottlePolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(4), 50, Duration.ofMinutes(1),
        Duration.ofMinutes(15));
    Instant t = Instant.parse("2026-10-17T10:00:00Z");
    for (int i = 0; i < 10; i++) {
      throttle.fail(k, p, t);
    }
    assertThat(throttle.check(k, p, t).retryAfterMillis()).isEqualTo(4000);
  }

  @Test
  void successResetsTheCountButNotAnActiveLock() {
    String k = key();
    ThrottlePolicy p = new ThrottlePolicy(1, Duration.ZERO, Duration.ZERO, 2, Duration.ofMinutes(15),
        Duration.ofMinutes(15));
    Instant t = Instant.now();
    throttle.fail(k, p, t);
    throttle.reset(k);
    assertThat(throttle.fail(k, p, t).failures()).isEqualTo(1);
    assertThat(throttle.fail(k, p, t).lockedNow()).isTrue();
    throttle.reset(k);
    assertThat(throttle.check(k, p, t).allowed()).isFalse();
  }

  @Test
  void perIpPolicyLocksAfterTheLimitWithoutDelays() {
    String k = key();
    ThrottlePolicy ip = ThrottlePolicy.perIp(3, Duration.ofMinutes(15));
    Instant t = Instant.now();
    for (int i = 0; i < 2; i++) {
      throttle.fail(k, ip, t);
      assertThat(throttle.check(k, ip, t).allowed()).isTrue();
    }
    assertThat(throttle.fail(k, ip, t).lockedNow()).isTrue();
    assertThat(throttle.check(k, ip, t).allowed()).isFalse();
  }

  @Test
  void keysCarryTheServicePrefixAndExpire() {
    String k = key();
    throttle.fail(k, LOGIN, Instant.now());
    assertThat(TestRedis.keys("identity:thr:" + k + "*")).hasSize(2);
    assertThat(TestRedis.admin().getExpire("identity:thr:" + k + ":n")).isPositive();
  }
}
