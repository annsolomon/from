package in.dexter.commerce.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The signed /r/{token}: round trip, tamper, expiry, wrong key, anonymous. */
class ClickTokenTest {
  static final byte[] KEY = "click-token-test-key-0123456789abcdef".getBytes();
  static final UUID DEAL = UUID.fromString("0192f1e2-0000-7000-8000-0000000d0001");
  static final UUID ACCOUNT = UUID.fromString("0192f1e2-0000-7000-8000-0000000a0001");
  static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

  final ClickToken tokens = new ClickToken(KEY);

  @Test
  void roundTripsAndIsUrlSafe() {
    String t = tokens.mint(new ClickToken.Claims(ClickToken.Kind.DEAL, DEAL, ACCOUNT, NOW.plusSeconds(600)));
    assertThat(t).hasSize(86).matches("[A-Za-z0-9_-]+");
    ClickToken.Verified v = tokens.verify(t, NOW);
    assertThat(v.outcome()).isEqualTo(ClickToken.Outcome.OK);
    assertThat(v.claims()).isEqualTo(new ClickToken.Claims(ClickToken.Kind.DEAL, DEAL, ACCOUNT,
        NOW.plusSeconds(600)));
    String anon = tokens.mint(new ClickToken.Claims(ClickToken.Kind.AD, DEAL, null, NOW.plusSeconds(1)));
    assertThat(tokens.verify(anon, NOW).claims().accountId()).isNull();
    assertThat(tokens.mint(new ClickToken.Claims(ClickToken.Kind.DEAL, DEAL, ACCOUNT, NOW))).isNotEqualTo(
        tokens.mint(new ClickToken.Claims(ClickToken.Kind.DEAL, DEAL, ACCOUNT, NOW)));
  }

  @Test
  void anAlteredByteOrAnotherKeyIsInvalid() {
    String t = tokens.mint(new ClickToken.Claims(ClickToken.Kind.DEAL, DEAL, ACCOUNT, NOW.plusSeconds(600)));
    byte[] raw = Base64.getUrlDecoder().decode(t);
    for (int i = 0; i < raw.length; i++) {
      byte[] bad = raw.clone();
      bad[i] ^= 0x01;
      String b = Base64.getUrlEncoder().withoutPadding().encodeToString(bad);
      assertThat(tokens.verify(b, NOW).outcome()).as("byte " + i).isEqualTo(ClickToken.Outcome.INVALID);
    }
    ClickToken other = new ClickToken("another-click-token-key-0123456789ab".getBytes());
    assertThat(other.verify(t, NOW).outcome()).isEqualTo(ClickToken.Outcome.INVALID);
    assertThat(tokens.verify("short", NOW).outcome()).isEqualTo(ClickToken.Outcome.INVALID);
    assertThat(tokens.verify(null, NOW).outcome()).isEqualTo(ClickToken.Outcome.INVALID);
  }

  @Test
  void expiresAtItsInstant() {
    String t = tokens.mint(new ClickToken.Claims(ClickToken.Kind.DEAL, DEAL, ACCOUNT, NOW.plusSeconds(60)));
    assertThat(tokens.verify(t, NOW.plusSeconds(59)).outcome()).isEqualTo(ClickToken.Outcome.OK);
    assertThat(tokens.verify(t, NOW.plusSeconds(60)).outcome()).isEqualTo(ClickToken.Outcome.EXPIRED);
  }
}
