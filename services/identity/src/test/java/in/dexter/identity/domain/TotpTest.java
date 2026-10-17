package in.dexter.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** RFC 6238 Appendix B (SHA1 column, 8 digits) plus the 6-digit form, windows and Base32. */
class TotpTest {
  static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

  @ParameterizedTest(name = "T={0}")
  @CsvSource({
      "59, 94287082",
      "1111111109, 07081804",
      "1111111111, 14050471",
      "1234567890, 89005924",
      "2000000000, 69279037",
      "20000000000, 65353130"})
  void rfc6238Sha1Vectors(long epochSeconds, String expected) {
    long step = Totp.step(Instant.ofEpochSecond(epochSeconds));
    assertThat(Totp.code(RFC_SECRET, step, 8)).isEqualTo(expected);
    // 6 digits = the same truncation mod 10^6 (what authenticator apps show)
    assertThat(Totp.code(RFC_SECRET, step, 6)).isEqualTo(expected.substring(2));
  }

  @Test
  void verifyAcceptsTheWindowAndReturnsTheMatchedStep() {
    Instant now = Instant.ofEpochSecond(1_234_567_890L);
    long step = Totp.step(now);
    assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step, 6), now, 1)).isEqualTo(step);
    assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1, 6), now, 1)).isEqualTo(step - 1);
    assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1, 6), now, 1)).isEqualTo(step + 1);
    assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2, 6), now, 1)).isEqualTo(-1);
    assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 2, 6), now, 1)).isEqualTo(-1);
    assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1, 6), now, 0)).isEqualTo(-1);
  }

  @Test
  void malformedCodesNeverMatch() {
    Instant now = Instant.ofEpochSecond(59);
    for (String bad : new String[] {null, "", "12345", "1234567", "abcdef", " 287082", "287 082"}) {
      assertThat(Totp.verify(RFC_SECRET, bad, now, 1)).as(String.valueOf(bad)).isEqualTo(-1);
    }
  }

  @Test
  void base32RoundTripAndRfc4648Vectors() {
    assertThat(Totp.Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    assertThat(new String(Totp.Base32.decode("mzxw6ytboi"), StandardCharsets.US_ASCII)).isEqualTo("foobar");
    byte[] s = Totp.newSecret();
    assertThat(s).hasSize(Totp.SECRET_BYTES);
    assertThat(Totp.Base32.decode(Totp.Base32.encode(s))).isEqualTo(s);
    assertThatThrownBy(() -> Totp.Base32.decode("not base32 1")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void otpauthUriHasTheStandardParameters() {
    String uri = Totp.otpauthUri("Dexter", "asha@example.com", RFC_SECRET);
    assertThat(uri).startsWith("otpauth://totp/Dexter:asha%40example.com?secret=")
        .contains("&issuer=Dexter").contains("&algorithm=SHA1").contains("&digits=6").contains("&period=30");
  }
}
