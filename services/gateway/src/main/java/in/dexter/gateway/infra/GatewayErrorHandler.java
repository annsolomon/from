package in.dexter.gateway.infra;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.ErrorCode;
import in.dexter.gateway.domain.GatewayErrors;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * (Ordered before Boot's default WebFlux error handler.) Every error the gateway itself answers becomes a coded ProblemDetail with the request id: no route → 404
 * {@code NOT_FOUND} (never a 401 for a path that does not exist), a dead or open-circuit service → 503
 * {@code UPSTREAM_UNAVAILABLE}, a slow one → 504 {@code UPSTREAM_TIMEOUT}. Errors a service returns pass through
 * untouched (they are responses, not exceptions).
 */
public final class GatewayErrorHandler implements WebExceptionHandler, Ordered {
  private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);
  private final Problems problems;

  public GatewayErrorHandler(Problems problems) {
    this.problems = problems;
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(ex);
    }
    ErrorCode code = classify(ex);
    String detail = code == CoreErrors.NOT_FOUND ? "No API route for this path"
        : code == GatewayErrors.UPSTREAM_TIMEOUT ? "The service did not answer in time"
        : code == GatewayErrors.UPSTREAM_UNAVAILABLE ? "The service is unavailable; retry shortly"
        : code.title();
    if (code == CoreErrors.INTERNAL_ERROR) {
      log.error("GATEWAY_UNHANDLED path={}", exchange.getRequest().getPath().value(), ex);
    } else if (code instanceof GatewayErrors) {
      RouteMatcher.Match m = exchange.getAttribute(GatewayAttributes.ROUTE);
      log.warn("GATEWAY_{} route={} cause={}", code.code(), m == null ? "-" : m.route().id(), root(ex));
    }
    if (code == GatewayErrors.UPSTREAM_UNAVAILABLE) {
      exchange.getResponse().getHeaders().set("Retry-After", "5");
    }
    return problems.write(exchange, code, detail);
  }

  static ErrorCode classify(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      String n = t.getClass().getName();
      if (t instanceof TimeoutException || n.endsWith("ReadTimeoutException")) {
        return GatewayErrors.UPSTREAM_TIMEOUT;
      }
      // connection refused/reset, premature close, open breaker: the service is (for now) not there
      if (t instanceof ConnectException || t instanceof java.io.IOException || n.endsWith("CallNotPermittedException")
          || n.endsWith("PrematureCloseException") || n.endsWith("ConnectTimeoutException")
          || n.endsWith("AbortedException") || n.endsWith("ServiceUnavailableException")) {
        return GatewayErrors.UPSTREAM_UNAVAILABLE;
      }
      // exceptions that carry their status as @ResponseStatus (e.g. Spring Cloud Gateway's own)
      org.springframework.web.bind.annotation.ResponseStatus rs = org.springframework.core.annotation.AnnotatedElementUtils
          .findMergedAnnotation(t.getClass(), org.springframework.web.bind.annotation.ResponseStatus.class);
      if (rs != null) {
        int s = rs.code().value();
        return s == 503 || s == 502 ? GatewayErrors.UPSTREAM_UNAVAILABLE : s == 504 ? GatewayErrors.UPSTREAM_TIMEOUT
            : s == 404 ? CoreErrors.NOT_FOUND : s < 500 ? CoreErrors.BAD_REQUEST : CoreErrors.INTERNAL_ERROR;
      }
      if (t instanceof ResponseStatusException rse) {
        int s = rse.getStatusCode().value();
        return switch (s) {
          case 404 -> CoreErrors.NOT_FOUND;
          case 405 -> CoreErrors.METHOD_NOT_ALLOWED;
          case 406 -> CoreErrors.NOT_ACCEPTABLE;
          case 413 -> CoreErrors.PAYLOAD_TOO_LARGE;
          case 415 -> CoreErrors.UNSUPPORTED_MEDIA_TYPE;
          case 502, 503 -> GatewayErrors.UPSTREAM_UNAVAILABLE;
          case 504 -> GatewayErrors.UPSTREAM_TIMEOUT;
          default -> s < 500 ? CoreErrors.BAD_REQUEST : CoreErrors.INTERNAL_ERROR;
        };
      }
    }
    return CoreErrors.INTERNAL_ERROR;
  }

  private static String root(Throwable ex) {
    Throwable t = ex;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    return t.getClass().getSimpleName();
  }

  @Override
  public int getOrder() {
    return -2;
  }
}
