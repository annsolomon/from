package in.dexter.gateway.infra;

import in.dexter.core.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * RFC 9457 ProblemDetails in the same shape the services produce (dexter-web {@code ProblemDetailsAdvice}): type,
 * title, status, detail, instance, code, requestId (+ extra properties).
 */
public final class Problems {
  public static final String TYPE_BASE = "https://docs.dexter.in/problems/";
  private static final MediaType PROBLEM = MediaType.valueOf("application/problem+json");
  private final JsonMapper json;

  public Problems(JsonMapper json) {
    this.json = json;
  }

  public Mono<Void> write(ServerWebExchange exchange, ErrorCode code, String detail) {
    return write(exchange, code, detail, Map.of());
  }

  public Mono<Void> write(ServerWebExchange exchange, ErrorCode code, String detail, Map<String, Object> extra) {
    ServerHttpResponse res = exchange.getResponse();
    if (res.isCommitted()) {
      return Mono.empty();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", TYPE_BASE + code.code().toLowerCase(Locale.ROOT).replace('_', '-'));
    body.put("title", code.title());
    body.put("status", code.status());
    body.put("detail", detail);
    body.put("instance", exchange.getRequest().getPath().value());
    body.put("code", code.code());
    body.putAll(extra);
    Object rid = exchange.getAttribute(GatewayAttributes.REQUEST_ID);
    if (rid != null) {
      body.put("requestId", rid);
    }
    byte[] bytes = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    res.setStatusCode(HttpStatusCode.valueOf(code.status()));
    res.getHeaders().setContentType(PROBLEM);
    res.getHeaders().setContentLength(bytes.length);
    DataBuffer buf = res.bufferFactory().wrap(bytes);
    return res.writeWith(Mono.just(buf));
  }
}
