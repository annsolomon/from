package in.dexter.gateway.infra;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 2. Header hygiene (MASTER_PLAN 7.2): identity-looking headers from clients ({@code X-Account-*}, {@code X-Actor-*},
 * {@code X-User-*}, roles, permissions, tenant, …) never reach a service — identity comes only from the JWT, which
 * every service verifies itself. Also resolves the client IP for rate limiting and activity.
 */
public final class StripIdentityHeadersFilter implements WebFilter, Ordered {
  /** Same family as dexter-test's ArchRules identity headers, minus Authorization (the token itself). */
  static final Pattern IDENTITY = Pattern.compile(
      "(?i)x-(account|user|tenant|principal|actor|role|roles|perm|perms|permissions|admin|demo-account|auth|"
          + "client-id|subject|sub|service)([-_].*)?");
  private final ClientIp clientIp;

  public StripIdentityHeadersFilter(ClientIp clientIp) {
    this.clientIp = clientIp;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    ServerHttpRequest req = exchange.getRequest();
    exchange.getAttributes().put(GatewayAttributes.CLIENT_IP,
        clientIp.resolve(req.getRemoteAddress(), req.getHeaders().getFirst("X-Forwarded-For")));
    List<String> strip = req.getHeaders().headerNames().stream().filter(n -> IDENTITY.matcher(n).matches()).toList();
    if (strip.isEmpty()) {
      return chain.filter(exchange);
    }
    ServerHttpRequest clean = req.mutate().headers(h -> strip.forEach(h::remove)).build();
    return chain.filter(exchange.mutate().request(clean).build());
  }

  @Override
  public int getOrder() {
    return FilterOrder.STRIP_IDENTITY;
  }
}
