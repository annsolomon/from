package in.dexter.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ClientCredentialsArgumentResolverTest {
  static String basic(String raw) {
    return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void parsesFormEncodedBasicCredentials() {
    var c = ClientCredentialsArgumentResolver.parse(basic("ledger:s%3Acret%2B1"));
    assertThat(c.clientId()).isEqualTo("ledger");
    assertThat(c.secret()).isEqualTo("s:cret+1");
    assertThat(ClientCredentialsArgumentResolver.parse("basic " + basic("a:b").substring(6)).clientId()).isEqualTo("a");
  }

  @Test
  void malformedHeadersResolveToNull() {
    assertThat(ClientCredentialsArgumentResolver.parse(null)).isNull();
    assertThat(ClientCredentialsArgumentResolver.parse("Bearer abc")).isNull();
    assertThat(ClientCredentialsArgumentResolver.parse("Basic !!!")).isNull();
    assertThat(ClientCredentialsArgumentResolver.parse(basic("no-colon"))).isNull();
    assertThat(ClientCredentialsArgumentResolver.parse(basic(":secret"))).isNull();
    assertThat(ClientCredentialsArgumentResolver.parse("Basic " + "A".repeat(2000))).isNull();
  }
}
