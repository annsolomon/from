package in.dexter.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.Fault;
import in.dexter.gateway.support.GatewayIT;
import in.dexter.gateway.support.Tokens;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Circuit breaker per route, response timeouts, and the SSE route without either (MASTER_PLAN P06 step 2.6 and 3).
 * Own context (own breaker registry) so tripping a breaker here cannot affect the route-table tests. The context's
 * response timeout is 3 s; the SSE stream below stays silent for 4 s between events and lasts ~12 s.
 */
class ResilienceIT extends GatewayIT {
  @DynamicPropertySource
  static void breakerProps(DynamicPropertyRegistry r) {
    r.add("dexter.gateway.breaker.minimum-calls", () -> "4");
    r.add("dexter.gateway.breaker.sliding-window", () -> "4");
    r.add("dexter.gateway.breaker.wait-open", () -> "60s");
  }

  @Test
  void sseIsNotCutAfterTenSeconds() {
    String sse = "event: tick\ndata: {\"n\":1}\n\nevent: tick\ndata: {\"n\":2}\n\nevent: tick\ndata: {\"n\":3}\n\n"
        + "event: tick\ndata: {\"n\":4}\n\n";
    BACKENDS.get("reco").stubFor(get(urlPathEqualTo("/v1/recommendations/stream")).atPriority(1)
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream")
            .withHeader("Cache-Control", "no-cache").withBody(sse).withChunkedDribbleDelay(4, 12_000)));
    Res r = send("GET", "/v1/recommendations/stream?ticket=t-" + System.nanoTime(), null, "X-Forwarded-For", ip());
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.millis()).as("the stream ran past 10 s").isGreaterThanOrEqualTo(10_000);
    assertThat(r.body()).contains("\"n\":1").contains("\"n\":4");
    assertThat(r.header("Content-Type")).startsWith("text/event-stream");
  }

  @Test
  void slowServiceIs504AfterTheRouteTimeout() {
    BACKENDS.get("planning").stubFor(get(urlPathEqualTo("/v1/budgets/slow")).atPriority(1)
        .willReturn(aResponse().withStatus(200).withFixedDelay(6_000)));
    Res r = send("GET", "/v1/budgets/slow", null, auth(Tokens.user(), "X-Forwarded-For", ip()));
    assertThat(r.status()).isEqualTo(504);
    assertThat(r.body()).contains("\"code\":\"UPSTREAM_TIMEOUT\"");
    assertThat(r.millis()).isBetween(2_500L, 5_900L);
  }

  @Test
  void breakerOpensOnFailuresAndStopsCallingTheService() {
    BACKENDS.get("commerce").stubFor(get(urlPathEqualTo("/v1/deals/broken")).atPriority(1)
        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    for (int i = 0; i < 4; i++) {
      Res r = send("GET", "/v1/deals/broken", null, "X-Forwarded-For", ip());
      assertThat(r.status()).as("failure " + i + " " + r).isIn(502, 503);
    }
    int calls = BACKENDS.get("commerce").findAll(anyRequestedFor(urlPathEqualTo("/v1/deals/broken"))).size();
    Res open = send("GET", "/v1/deals", null, "X-Forwarded-For", ip());
    assertThat(open.status()).as("circuit open for the whole commerce route").isEqualTo(503);
    assertThat(open.body()).contains("\"code\":\"UPSTREAM_UNAVAILABLE\"");
    assertThat(open.header("Retry-After")).isNotNull();
    assertThat(BACKENDS.get("commerce").findAll(anyRequestedFor(urlPathEqualTo("/v1/deals/broken"))))
        .hasSize(calls);
    assertThat(send("GET", "/v1/cards", null, auth(Tokens.user(), "X-Forwarded-For", ip())).status())
        .as("other routes unaffected").isEqualTo(200);
  }
}
