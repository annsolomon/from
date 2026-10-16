package in.dexter.core.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestIdsTest {
  @Test
  void acceptsSafeIdsOnly() {
    assertThat(RequestIds.acceptOrGenerate("trail-abc.1:2")).isEqualTo("trail-abc.1:2");
    assertThat(RequestIds.acceptOrGenerate("bad id\r\nX-Injected: 1")).hasSize(36);
    assertThat(RequestIds.acceptOrGenerate("x".repeat(129))).hasSize(36);
    assertThat(RequestIds.acceptOrGenerate(null)).hasSize(36);
  }
}
