package in.dexter.gateway.infra;

import in.dexter.core.error.CoreErrors;
import in.dexter.gateway.domain.RouteClass;
import in.dexter.security.AuthContext;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 5. Rate limits (MASTER_PLAN 7.3): token bucket per route class, keyed by the verified account, else the client IP.
 * Every limited response carries {@code RateLimit-Policy} / {@code RateLimit} (IETF httpapi draft, structured
 * fields); a refusal is 429 {@code RATE_LIMITED} with {@code Retry-After}. Redis unavailable: sign-in routes fail
 * closed (503), everything else fails open (MASTER_PLAN P19 resilience rule).
 */
public final class RateLimitFilter implements WebFilter, Ordered {
  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
  private final RedisTokenBucket bucket;
  private final Map<RouteClass, Integer> limits;
  private final Problems problems;
  private final Clock clock;

  public RateLimitFilter(RedisTokenBucket bucket, Map<RouteClass, Integer> limits, Problems problems, Clock clock) {
    this.bucket = bucket;
    this.limits = new EnumMap<>(limits);
    this.problems = problems;
    this.clock = clock;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    RouteMatcher.Match m = exchange.getAttribute(GatewayAttributes.ROUTE);
    if (m == null) {
      return chain.filter(exchange);
    }
    RouteClass cls = m.routeClass();
    int limit = limits.get(cls);
    AuthContext who = exchange.getAttribute(GatewayAttributes.AUTH);
    String key = cls.id() + ":" + (who != null && who.accountId() != null ? "a:" + who.accountId()
        : "i:" + exchange.getAttributeOrDefault(GatewayAttributes.CLIENT_IP, "unknown"));
    return bucket.take(key, limit, clock.millis())
        .map(java.util.Optional::of)
        .onErrorResume(e -> {
          log.warn("GATEWAY_RATE_LIMIT_UNAVAILABLE class={}: {}", cls.id(), e.toString());
          return Mono.just(java.util.Optional.empty());
        })
        .defaultIfEmpty(java.util.Optional.empty())
        .flatMap(o -> {
          if (o.isEmpty()) {
            if (cls == RouteClass.AUTH) {
              exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "2");
              return problems.write(exchange, CoreErrors.SERVICE_UNAVAILABLE, "Sign-in is temporarily unavailable",
                  Map.of("retryAfterSeconds", 2));
            }
            return chain.filter(exchange); // fail open
          }
          RedisTokenBucket.Result r = o.get();
          HttpHeaders h = exchange.getResponse().getHeaders();
          h.set("RateLimit-Policy", "\"" + cls.id() + "\";q=" + limit + ";w=60");
          h.set("RateLimit", "\"" + cls.id() + "\";r=" + r.remaining() + ";t=" + seconds(r.fullInMillis()));
          if (r.allowed()) {
            return chain.filter(exchange);
          }
          long retry = Math.max(1, seconds(r.retryAfterMillis()));
          h.set(HttpHeaders.RETRY_AFTER, Long.toString(retry));
          log.info("GATEWAY_RATE_LIMITED class={} route={}", cls.id(), m.route().id());
          return problems.write(exchange, CoreErrors.RATE_LIMITED, "Too many requests; slow down",
              Map.of("retryAfterSeconds", retry));
        });
  }

  private static long seconds(long millis) {
    return (millis + 999) / 1000;
  }

  @Override
  public int getOrder() {
    return FilterOrder.RATE_LIMIT;
  }
}
