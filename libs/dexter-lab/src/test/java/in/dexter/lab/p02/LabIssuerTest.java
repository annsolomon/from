package in.dexter.lab.p02;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.core.error.ValidationException;
import in.dexter.core.time.MutableClock;
import in.dexter.security.AuthContext;
import in.dexter.security.jwt.JwksCache;
import in.dexter.security.jwt.JwtSettings;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.security.jwt.TokenRejectedException;
import in.dexter.security.jwt.TokenRejectedException.Reason;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Every playground defect is rejected by the real verifier for the reason the trail page shows. */
class LabIssuerTest {
  final LabIssuer issuer = new LabIssuer();
  final MutableClock clock = new MutableClock(Instant.now());
  final JwtVerifier verifier = new JwtVerifier(JwtSettings.of(LabIssuer.ISSUER, LabIssuer.AUDIENCE),
      JwksCache.withDefaults(issuer.source(), clock), clock);

  static Reason reason(Runnable r) {
    try {
      r.run();
    } catch (TokenRejectedException e) {
      return e.reason();
    }
    throw new AssertionError("token was accepted");
  }

  @Test
  void validTokensForEveryPersona() {
    AuthContext asha = verifier.verify(issuer.token("asha", "none"));
    assertThat(asha.tenant().accountId()).isEqualTo(LabIssuer.ASHA);
    assertThat(verifier.verify(issuer.token("ravi", null)).accountId()).isEqualTo(LabIssuer.RAVI);
    AuthContext svc = verifier.verify(issuer.token("svc-reco", "none"));
    assertThat(svc.isService()).isTrue();
    assertThat(svc.service()).isEqualTo("reco");
  }

  @ParameterizedTest
  @CsvSource({
      "expired, EXPIRED",
      "not-yet-valid, NOT_YET_VALID",
      "lifetime-too-long, LIFETIME",
      "wrong-audience, AUDIENCE",
      "wrong-issuer, ISSUER",
      "wrong-typ, TYPE",
      "hs256-confusion, ALGORITHM",
      "unknown-kid, KID_UNKNOWN",
      "embedded-jwk, HEADER",
      "bad-signature, SIGNATURE"})
  void eachDefectIsRejectedForItsReason(String defect, Reason expected) {
    String token = issuer.token("asha", defect);
    assertThat(reason(() -> verifier.verify(token))).as(defect).isEqualTo(expected);
    assertThat(LabIssuer.DEFECTS.get(defect)).isEqualTo(expected.name());
  }

  @Test
  void algNoneIsRejected() {
    String token = issuer.token("asha", "alg-none");
    assertThat(token).endsWith(".");
    assertThat(reason(() -> verifier.verify(token))).isIn(Reason.MALFORMED, Reason.ALGORITHM);
  }

  @Test
  void rotationIsPickedUpByAnUnknownKidRefetchAndRetiredKeysDropAtTheNextRefresh() {
    String old = issuer.token("asha", "none");
    verifier.verify(old);
    long fetches = issuer.fetches();
    String kid = issuer.rotate();
    assertThat(issuer.publishedKids()).hasSize(2).contains(kid);
    String fresh = issuer.token("asha", "none");
    clock.advance(Duration.ofSeconds(11)); // unknown-kid refetches are limited to one per 10 s
    verifier.verify(fresh);
    assertThat(issuer.fetches()).isEqualTo(fetches + 1);
    verifier.verify(old); // still published
    assertThat(issuer.retireOld()).hasSize(1);
    verifier.verify(old); // the verifier's cached JWKS still has it
    clock.advance(Duration.ofMinutes(6)); // past the JWKS TTL: the next unknown-kid lookup refreshes
    verifier.verify(fresh);
    assertThat(reason(() -> verifier.verify(old))).isEqualTo(Reason.KID_UNKNOWN);
  }

  @Test
  void unknownPersonaOrDefectIs400() {
    assertThatThrownBy(() -> issuer.token("mallory", "none")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> issuer.token("asha", "nope")).isInstanceOf(ValidationException.class);
  }

  @Test
  void flippedSignatureStaysWellFormed() {
    String t = issuer.token("asha", "none");
    String f = LabIssuer.flipSignature(t);
    assertThat(f).hasSameSizeAs(t).isNotEqualTo(t);
    assertThat(f.substring(0, f.lastIndexOf('.'))).isEqualTo(t.substring(0, t.lastIndexOf('.')));
  }
}
