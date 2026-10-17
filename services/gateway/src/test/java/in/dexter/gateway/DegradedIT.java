package in.dexter.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.gateway.support.GatewayBase;
import in.dexter.gateway.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Redis and Kafka unreachable (own context): reads keep working (limiter fails open), sign-in fails closed (503),
 * and activity publishing never slows a request down.
 */
class DegradedIT extends GatewayBase {
  @DynamicPropertySource
  static void deadInfra(DynamicPropertyRegistry r) {
    r.add("spring.data.redis.host", () -> "127.0.0.1");
    r.add("spring.data.redis.port", () -> "1");
    r.add("spring.data.redis.timeout", () -> "200ms");
    r.add("dexter.gateway.activity.bootstrap-servers", () -> "127.0.0.1:1");
  }

  @Test
  void readsFailOpenAndSignInFailsClosed() {
    long worst = 0;
    for (int i = 0; i < 20; i++) {
      Res r = send("GET", "/v1/cards", null, auth(Tokens.user(), "X-Forwarded-For", ip()));
      assertThat(r.status()).as(r.toString()).isEqualTo(200);
      assertThat(r.header("RateLimit")).as("no limiter headers without Redis").isNull();
      worst = Math.max(worst, r.millis());
    }
    assertThat(worst).as("neither Redis nor Kafka being down blocks a request").isLessThan(2_000);
    Res login = send("POST", "/v1/auth/login", "{}", "X-Forwarded-For", ip());
    assertThat(login.status()).isEqualTo(503);
    assertThat(login.body()).contains("\"code\":\"SERVICE_UNAVAILABLE\"");
    assertThat(login.header("Retry-After")).isEqualTo("2");
  }
}
