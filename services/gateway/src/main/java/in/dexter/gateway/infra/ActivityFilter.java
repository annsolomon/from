package in.dexter.gateway.infra;

import in.dexter.contracts.events.gateway.ActivityRecordedV1;
import in.dexter.security.AuthContext;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Outermost filter: times each routed request and hands a sample to {@link ActivityPublisher} (never waits). */
public final class ActivityFilter implements WebFilter, Ordered {
  private final ActivityPublisher publisher;
  private final double sampleRate;

  public ActivityFilter(ActivityPublisher publisher, double sampleRate) {
    this.publisher = publisher;
    this.sampleRate = sampleRate;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    long started = System.nanoTime();
    return chain.filter(exchange).doFinally(signal -> {
      RouteMatcher.Match m = exchange.getAttribute(GatewayAttributes.ROUTE);
      if (m == null || (sampleRate < 1.0 && ThreadLocalRandom.current().nextDouble() >= sampleRate)) {
        return;
      }
      HttpStatusCode sc = exchange.getResponse().getStatusCode();
      AuthContext who = exchange.getAttribute(GatewayAttributes.AUTH);
      String rid = exchange.getAttributeOrDefault(GatewayAttributes.REQUEST_ID, "unknown");
      publisher.publish(who == null ? null : who.accountId(), new ActivityRecordedV1(rid, m.route().service(),
          m.pattern(), m.routeClass().id(), exchange.getRequest().getMethod().name(),
          sc == null ? 200 : sc.value(), (System.nanoTime() - started) / 1_000_000, Instant.now().toString()));
    });
  }

  @Override
  public int getOrder() {
    return FilterOrder.ACTIVITY;
  }
}
