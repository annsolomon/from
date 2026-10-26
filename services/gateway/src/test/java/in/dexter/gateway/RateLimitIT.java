package in.dexter.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.gateway.infra.RedisTokenBucket;
import in.dexter.gateway.support.GatewayIT;
import in.dexter.gateway.support.Tokens;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/** MASTER_PLAN 7.3 rate limits: token bucket per class, per account or IP; 429 + headers. */
class RateLimitIT extends GatewayIT {
  @Autowired
  ReactiveStringRedisTemplate redis;

  @Test
  void authClassAllowsTenPerMinutePerIpThen429WithHeaders() {
    String ip = ip();
    for (int i = 1; i <= 10; i++) {
      Res r = send("POST", "/v1/auth/login", "{}", "X-Forwarded-For", ip);
      assertThat(r.status()).as("attempt " + i).isEqualTo(200);
      assertThat(r.header("RateLimit-Policy")).isEqualTo("\"auth\";q=10;w=60");
      assertThat(r.header("RateLimit")).startsWith("\"auth\";r=" + (10 - i) + ";t=");
    }
    Res limited = send("POST", "/v1/auth/login", "{}", "X-Forwarded-For", ip);
    assertThat(limited.status()).isEqualTo(429);
    assertThat(limited.body()).contains("\"code\":\"RATE_LIMITED\"").contains("\"retryAfterSeconds\"");
    assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(1L, 7L);
    assertThat(limited.header("RateLimit")).startsWith("\"auth\";r=0;");
    assertThat(send("POST", "/v1/auth/login", "{}", "X-Forwarded-For", ip()).status())
        .as("another IP has its own bucket").isEqualTo(200);
  }

  @Test
  void authenticatedCallersAreLimitedPerAccountNotPerIp() {
    String ip = ip();
    UUID a = UUID.randomUUID();
    String ta = Tokens.user(a);
    for (int i = 0; i < 60; i++) {
      assertThat(send("POST", "/v1/calc/emi", "{}", auth(ta, "X-Forwarded-For", ip)).status()).as("calc " + i)
          .isEqualTo(200);
    }
    // The bucket refills continuously (1 token/s at 60/min), so a slow runner may earn a token or two back while
    // the 60 calls run: the limit must bite within a few more calls, not necessarily on the 61st.
    Res limited = null;
    for (int i = 0; i < 5 && (limited == null || limited.status() != 429); i++) {
      limited = send("POST", "/v1/calc/emi", "{}", auth(ta, "X-Forwarded-For", ip));
    }
    assertThat(limited.status()).isEqualTo(429);
    assertThat(limited.header("RateLimit-Policy")).isEqualTo("\"calc\";q=60;w=60");
    assertThat(send("POST", "/v1/calc/emi", "{}", auth(Tokens.user(), "X-Forwarded-For", ip)).status())
        .as("another account behind the same IP").isEqualTo(200);
    assertThat(send("GET", "/v1/cards", null, auth(ta, "X-Forwarded-For", ip)).status())
        .as("other classes are separate buckets").isEqualTo(200);
  }

  @Test
  void bucketRefillsContinuouslyWithTheGivenClock() {
    RedisTokenBucket b = new RedisTokenBucket(redis, Duration.ofSeconds(2));
    String key = "test:" + UUID.randomUUID();
    long t = 1_800_000_000_000L;
    for (int i = 0; i < 6; i++) {
      assertThat(b.take(key, 6, t).toFuture().join().allowed()).isTrue();
    }
    RedisTokenBucket.Result empty = b.take(key, 6, t).toFuture().join();
    assertThat(empty.allowed()).isFalse();
    assertThat(empty.retryAfterMillis()).isEqualTo(10_000);
    assertThat(b.take(key, 6, t + 9_999).toFuture().join().allowed()).isFalse();
    RedisTokenBucket.Result refilled = b.take(key, 6, t + 20_000).toFuture().join();
    assertThat(refilled.allowed()).as("one token per 10 s").isTrue();
    RedisTokenBucket.Result full = b.take(key, 6, t + 600_000).toFuture().join();
    assertThat(full.remaining()).as("capped at capacity").isEqualTo(5);
  }
}
