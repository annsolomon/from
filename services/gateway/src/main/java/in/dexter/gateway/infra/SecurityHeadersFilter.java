package in.dexter.gateway.infra;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 7. Response security headers (MASTER_PLAN 7.3) on every response, set just before commit so they override
 * whatever a service sent: nosniff, no referrer, no framing, a CSP that allows nothing (the API serves JSON and
 * event streams, never documents; the admin console is a separate origin), HSTS where TLS terminates upstream.
 */
public final class SecurityHeadersFilter implements WebFilter, Ordered {
  static final String CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
  static final String HSTS = "max-age=31536000; includeSubDomains";
  private final boolean hsts;

  public SecurityHeadersFilter(boolean hsts) {
    this.hsts = hsts;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    exchange.getResponse().beforeCommit(() -> {
      HttpHeaders h = exchange.getResponse().getHeaders();
      h.set("X-Content-Type-Options", "nosniff");
      h.set("Referrer-Policy", "no-referrer");
      h.set("X-Frame-Options", "DENY");
      h.set("Content-Security-Policy", CSP);
      h.set("Cross-Origin-Resource-Policy", "same-site");
      if (hsts) {
        h.set("Strict-Transport-Security", HSTS);
      }
      h.remove("Server");
      h.remove("X-Powered-By");
      return Mono.empty();
    });
    return chain.filter(exchange);
  }

  @Override
  public int getOrder() {
    return FilterOrder.SECURITY_HEADERS;
  }
}
