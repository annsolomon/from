package in.dexter.security;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.security.web.BearerTokens;
import org.junit.jupiter.api.Test;

class BearerTokensTest {
  @Test
  void acceptsOneBearerCredentialCaseInsensitively() {
    assertThat(BearerTokens.extract("Bearer abc.def.ghi")).isEqualTo("abc.def.ghi");
    assertThat(BearerTokens.extract("bearer abc.def.ghi")).isEqualTo("abc.def.ghi");
    assertThat(BearerTokens.extract("BEARER   abc.def.ghi  ")).isEqualTo("abc.def.ghi");
  }

  @Test
  void rejectsEverythingElse() {
    assertThat(BearerTokens.extract(null)).isNull();
    assertThat(BearerTokens.extract("")).isNull();
    assertThat(BearerTokens.extract("Bearer ")).isNull();
    assertThat(BearerTokens.extract("Basic dXNlcjpwYXNz")).isNull();
    assertThat(BearerTokens.extract("Bearer a b")).isNull();
    assertThat(BearerTokens.extract("Bearer a,Bearer b")).isNull();
    assertThat(BearerTokens.extract("Bearerabc")).isNull();
  }
}
