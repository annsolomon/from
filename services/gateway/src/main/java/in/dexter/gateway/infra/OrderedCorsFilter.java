package in.dexter.gateway.infra;

import java.time.Duration;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 8. CORS allowlist per profile (MASTER_PLAN 7.3). Placed before the token check so a preflight (no credentials by
 * definition) is answered here; an origin outside the list gets 403 and no CORS headers. No credentials mode: the
 * API uses bearer tokens, never cookies.
 */
public final class OrderedCorsFilter implements WebFilter, Ordered {
  static final List<String> ALLOWED_HEADERS = List.of("Authorization", "Content-Type", "Idempotency-Key", "If-Match",
      "If-None-Match", "X-Request-Id", "Last-Event-ID");
  static final List<String> EXPOSED_HEADERS = List.of("ETag", "Location", "X-Request-Id", "Idempotent-Replayed",
      "RateLimit", "RateLimit-Policy", "Retry-After", "WWW-Authenticate");
  private final CorsWebFilter delegate;
  private final boolean active;

  public OrderedCorsFilter(List<String> allowedOrigins, Duration maxAge) {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    active = !allowedOrigins.isEmpty();
    if (active) {
      CorsConfiguration c = new CorsConfiguration();
      c.setAllowedOrigins(allowedOrigins);
      c.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
      c.setAllowedHeaders(ALLOWED_HEADERS);
      c.setExposedHeaders(EXPOSED_HEADERS);
      c.setAllowCredentials(false);
      c.setMaxAge(maxAge);
      source.registerCorsConfiguration("/**", c);
    }
    delegate = new CorsWebFilter(source);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    return delegate.filter(exchange, chain);
  }

  @Override
  public int getOrder() {
    return FilterOrder.CORS;
  }
}
