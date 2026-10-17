package in.dexter.gateway.infra;

import in.dexter.core.request.RequestIds;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 1. X-Request-Id (MASTER_PLAN 9): a sane client id is kept, anything else replaced by a UUIDv7; forwarded to the
 * service and echoed on every response, including the gateway's own errors.
 */
public final class RequestIdFilter implements WebFilter, Ordered {
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String id = RequestIds.acceptOrGenerate(exchange.getRequest().getHeaders().getFirst(RequestIds.HEADER));
    exchange.getAttributes().put(GatewayAttributes.REQUEST_ID, id);
    ServerHttpRequest req = exchange.getRequest().mutate().headers(h -> h.set(RequestIds.HEADER, id)).build();
    exchange.getResponse().beforeCommit(() -> {
      exchange.getResponse().getHeaders().set(RequestIds.HEADER, id);
      return Mono.empty();
    });
    return chain.filter(exchange.mutate().request(req).build());
  }

  @Override
  public int getOrder() {
    return FilterOrder.REQUEST_ID;
  }
}
