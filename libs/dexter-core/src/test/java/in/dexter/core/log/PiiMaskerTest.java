package in.dexter.core.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PiiMaskerTest {
  @Test
  void masksPii() {
    assertThat(PiiMasker.mask("login asha@demo.in ok")).isEqualTo("login a***@demo.in ok");
    assertThat(PiiMasker.mask("phone +91 98765 43210 x")).isEqualTo("phone [PHONE-10] x");
    assertThat(PiiMasker.mask("phone 9876543210")).isEqualTo("phone [PHONE-10]");
    assertThat(PiiMasker.mask("card 4111 1111 1111 1111 end")).isEqualTo("card [CARD-1111] end");
    assertThat(PiiMasker.mask("Authorization: Bearer abcdef1234567890")).isEqualTo("Authorization: Bearer [TOKEN]");
    assertThat(PiiMasker.mask("t=eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl")).isEqualTo("t=[JWT]");
    assertThat(PiiMasker.mask("password=hunter22 next")).isEqualTo("password=[REDACTED] next");
  }

  @Test
  void leavesOrdinaryTextAlone() {
    String s = "GET /v1/expenses?limit=50 200 in 14ms id=0192f0c1-7d3e-7abc-8def-0123456789ab amount=12345";
    assertThat(PiiMasker.mask(s)).isEqualTo(s);
    assertThat(PiiMasker.mask(null)).isNull();
  }
}
