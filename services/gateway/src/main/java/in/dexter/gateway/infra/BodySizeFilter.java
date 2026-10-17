package in.dexter.gateway.infra;

import in.dexter.core.error.CoreErrors;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 3. Body size limit (MASTER_PLAN 7.3: 256 KB; uploads go through presigned URLs). A declared Content-Length over
 * the limit is refused before anything is read; a chunked body is counted as it streams and cut at the limit.
 */
public final class BodySizeFilter implements WebFilter, Ordered {
  private final long max;
  private final Problems problems;

  public BodySizeFilter(long max, Problems problems) {
    this.max = max;
    this.problems = problems;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    long declared = exchange.getRequest().getHeaders().getContentLength();
    if (declared > max) {
      return problems.write(exchange, CoreErrors.PAYLOAD_TOO_LARGE, "The request body is larger than " + max + " bytes");
    }
    if (declared >= 0) {
      return chain.filter(exchange);
    }
    AtomicLong seen = new AtomicLong();
    ServerHttpRequest counted = new ServerHttpRequestDecorator(exchange.getRequest()) {
      @Override
      public Flux<DataBuffer> getBody() {
        return super.getBody().map(b -> {
          if (seen.addAndGet(b.readableByteCount()) > max) {
            DataBufferUtils.release(b);
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "body over the limit");
          }
          return b;
        });
      }
    };
    return chain.filter(exchange.mutate().request(counted).build());
  }

  @Override
  public int getOrder() {
    return FilterOrder.BODY_SIZE;
  }
}
