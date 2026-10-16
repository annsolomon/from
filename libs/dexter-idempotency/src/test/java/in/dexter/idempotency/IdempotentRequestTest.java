package in.dexter.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import tools.jackson.databind.json.JsonMapper;

class IdempotentRequestTest {
  static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void sameMethodPathAndBodyGiveTheSameFingerprint() {
    IdempotentRequest a = IdempotentRequest.of("post", "/v1/notes", "{\"b\":2,\"a\":1}", MAPPER);
    IdempotentRequest b = IdempotentRequest.of("POST", "/v1/notes", Map.of("a", 1, "b", 2), MAPPER);
    assertThat(a).isEqualTo(b);
    assertThat(a.sameAs(b.method(), b.path(), b.hash())).isTrue();
  }

  @Test
  void anyDifferenceChangesTheFingerprint() {
    IdempotentRequest base = IdempotentRequest.of("POST", "/v1/notes", "{\"a\":1}", MAPPER);
    assertThat(IdempotentRequest.of("PUT", "/v1/notes", "{\"a\":1}", MAPPER)).isNotEqualTo(base);
    assertThat(IdempotentRequest.of("POST", "/v1/other", "{\"a\":1}", MAPPER)).isNotEqualTo(base);
    assertThat(IdempotentRequest.of("POST", "/v1/notes", "{\"a\":2}", MAPPER)).isNotEqualTo(base);
  }

  @Test
  void hashIsDefensivelyCopied() {
    IdempotentRequest r = IdempotentRequest.of("POST", "/x", "{}", MAPPER);
    byte[] h = r.hash();
    h[0] ^= 1;
    assertThat(r.hash()).isNotEqualTo(h);
    assertThatThrownBy(() -> new IdempotentRequest("POST", "/x", new byte[5])).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lockTimeoutIsRecognisedThroughSpringTranslation() {
    SQLException pg = new SQLException("canceling statement due to lock timeout", "55P03");
    assertThat(IdempotencyEngine.isLockTimeout(new CannotAcquireLockException("x", pg))).isTrue();
    assertThat(IdempotencyEngine.isLockTimeout(new SQLException("other", "23505"))).isFalse();
  }

  @Test
  void apiResponseValidatesHeaders() {
    assertThat(ApiResponse.created("/v1/notes/1", Map.of()).headers()).containsEntry("Location", "/v1/notes/1");
    assertThatThrownBy(() -> new ApiResponse(201, Map.of("Bad Header", "x"), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ApiResponse.of(99, null)).isInstanceOf(IllegalArgumentException.class);
  }
}
