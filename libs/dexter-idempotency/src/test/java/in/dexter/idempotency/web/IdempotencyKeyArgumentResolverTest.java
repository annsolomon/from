package in.dexter.idempotency.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.idempotency.IdempotentResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class IdempotencyKeyArgumentResolverTest {
  @Test
  void parsesCanonicalUuids() {
    UUID k = UUID.randomUUID();
    assertThat(IdempotencyKeyArgumentResolver.parse(k.toString(), true)).isEqualTo(k);
    assertThat(IdempotencyKeyArgumentResolver.parse(" " + k.toString().toUpperCase() + " ", true)).isEqualTo(k);
  }

  @Test
  void missingKey() {
    assertThat(IdempotencyKeyArgumentResolver.parse(null, false)).isNull();
    assertThat(IdempotencyKeyArgumentResolver.parse(" ", false)).isNull();
    assertThatThrownBy(() -> IdempotencyKeyArgumentResolver.parse(null, true))
        .isInstanceOfSatisfying(DexterException.class,
            e -> assertThat(e.errorCode()).isEqualTo(CoreErrors.IDEMPOTENCY_KEY_REQUIRED));
  }

  @Test
  void garbageKey() {
    assertThatThrownBy(() -> IdempotencyKeyArgumentResolver.parse("not-a-uuid", false))
        .isInstanceOfSatisfying(DexterException.class,
            e -> assertThat(e.errorCode()).isEqualTo(CoreErrors.INVALID_ID));
  }

  @Test
  void replayHeader() {
    ResponseEntity<String> first = IdempotentResponses.toEntity(
        new IdempotentResponse(201, Map.of("Location", "/n/1"), "{\"id\":1}", false));
    ResponseEntity<String> again = IdempotentResponses.toEntity(
        new IdempotentResponse(201, Map.of("Location", "/n/1"), "{\"id\":1}", true));
    assertThat(first.getHeaders().getFirst(IdempotentResponse.REPLAYED_HEADER)).isNull();
    assertThat(again.getHeaders().getFirst(IdempotentResponse.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(again.getBody()).isEqualTo(first.getBody());
    assertThat(again.getStatusCode().value()).isEqualTo(201);
  }
}
