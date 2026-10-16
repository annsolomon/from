package in.dexter.idempotency.web;

import in.dexter.idempotency.IdempotentResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Turns an {@link IdempotentResponse} into the HTTP response. The body is already JSON text, so it is
 * written as-is (needs {@code StringHttpMessageConverter} ahead of Jackson, which is Spring Boot's
 * default order); replays carry {@code Idempotent-Replayed: true}.
 */
public final class IdempotentResponses {
  private IdempotentResponses() {}

  public static ResponseEntity<String> toEntity(IdempotentResponse r) {
    HttpHeaders h = new HttpHeaders();
    r.headers().forEach(h::set);
    if (r.replayed()) {
      h.set(IdempotentResponse.REPLAYED_HEADER, "true");
    }
    if (r.body() == null) {
      return ResponseEntity.status(r.status()).headers(h).build();
    }
    h.setContentType(MediaType.APPLICATION_JSON);
    return ResponseEntity.status(r.status()).headers(h).body(r.body());
  }
}
