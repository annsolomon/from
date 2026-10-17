package in.dexter.gateway.infra;

import in.dexter.core.error.CoreErrors;
import in.dexter.security.AuthContext;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.security.jwt.TokenRejectedException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 4. JWT pre-validation (MASTER_PLAN 7.2: an early rejection only — every service verifies the token again).
 * Public routes and paths without a route pass (an unknown path is a 404, not a 401). Otherwise a Bearer access
 * token must verify against identity's JWKS (signature, issuer, audience {@code gateway}, expiry, type); service
 * tokens are refused at the edge. Verification runs off the event loop (a JWKS refresh is a blocking HTTP call).
 */
public final class JwtPreCheckFilter implements WebFilter, Ordered {
  private static final Logger log = LoggerFactory.getLogger(JwtPreCheckFilter.class);
  static final String REALM = "Bearer realm=\"dexter\"";
  private final JwtVerifier verifier;
  private final RouteMatcher routes;
  private final Problems problems;

  public JwtPreCheckFilter(JwtVerifier verifier, RouteMatcher routes, Problems problems) {
    this.verifier = verifier;
    this.routes = routes;
    this.problems = problems;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String method = exchange.getRequest().getMethod().name();
    RouteMatcher.Match m = routes.match(method, exchange.getRequest().getPath().value());
    if (m == null) {
      return chain.filter(exchange);
    }
    exchange.getAttributes().put(GatewayAttributes.ROUTE, m);
    if (m.publicRoute()) {
      return chain.filter(exchange);
    }
    String token = bearer(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    if (token == null) {
      exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, REALM);
      return problems.write(exchange, CoreErrors.UNAUTHENTICATED, "A Bearer access token is required");
    }
    return Mono.fromCallable(() -> verifier.verify(token))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(ctx -> {
          if (ctx.kind() == AuthContext.Kind.SERVICE) {
            return problems.write(exchange, CoreErrors.FORBIDDEN, "Service tokens are not accepted at the edge");
          }
          exchange.getAttributes().put(GatewayAttributes.AUTH, ctx);
          return chain.filter(exchange);
        })
        .onErrorResume(TokenRejectedException.class, e -> {
          if (e.reason() == TokenRejectedException.Reason.JWKS_UNAVAILABLE) {
            log.error("GATEWAY_JWKS_UNAVAILABLE {}", e.getMessage());
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "5");
            return problems.write(exchange, CoreErrors.SERVICE_UNAVAILABLE, "Authentication is temporarily unavailable",
                Map.of("retryAfterSeconds", 5));
          }
          log.info("GATEWAY_AUTH_REJECTED reason={} route={}", e.reason(), m.route().id());
          exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, REALM + ", error=\"invalid_token\"");
          return problems.write(exchange, CoreErrors.UNAUTHENTICATED, "The access token is invalid or expired");
        });
  }

  static String bearer(String header) {
    if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return null;
    }
    String t = header.substring(7).strip();
    return t.isEmpty() || t.length() > 8192 ? null : t;
  }

  @Override
  public int getOrder() {
    return FilterOrder.JWT;
  }
}
