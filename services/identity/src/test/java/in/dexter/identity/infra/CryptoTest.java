package in.dexter.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import in.dexter.core.time.Clocks;
import in.dexter.identity.app.KeyCrypto;
import in.dexter.identity.app.SigningKeyStore.KeyRow;
import in.dexter.identity.app.TokenClaimsSpec;
import in.dexter.security.AuthContext;
import in.dexter.security.jwt.JwksCache;
import in.dexter.security.jwt.JwtSettings;
import in.dexter.security.jwt.JwtVerifier;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** AES-GCM envelope pieces and the ES256 token contract, checked against dexter-security's real verifier. */
class CryptoTest {
  static in.dexter.security.jwt.JwksSource source(String json) {
    return () -> {
      try {
        return JWKSet.parse(json);
      } catch (java.text.ParseException e) {
        throw new java.io.IOException(e);
      }
    };
  }

  static byte[] key() {
    byte[] k = new byte[32];
    new SecureRandom().nextBytes(k);
    return k;
  }

  @Test
  void aeadRoundTripAndTamperDetection() {
    byte[] k = key();
    byte[] sealed = Aead.seal(k, "acct:phone".getBytes(), "+919876543210".getBytes());
    assertThat(new String(Aead.open(k, "acct:phone".getBytes(), sealed), StandardCharsets.UTF_8)).isEqualTo("+919876543210");
    assertThatThrownBy(() -> Aead.open(k, "other:phone".getBytes(), sealed)).as("aad binds the row and field")
        .isInstanceOf(IllegalStateException.class);
    byte[] flipped = sealed.clone();
    flipped[flipped.length - 1] ^= 1;
    assertThatThrownBy(() -> Aead.open(k, "acct:phone".getBytes(), flipped)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> Aead.open(key(), "acct:phone".getBytes(), sealed)).isInstanceOf(IllegalStateException.class);
    assertThat(Aead.seal(k, new byte[0], new byte[] {1})).isNotEqualTo(Aead.seal(k, new byte[0], new byte[] {1}));
  }

  @Test
  void kekRingRotation() {
    byte[] k1 = key();
    byte[] k2 = key();
    KekRing old = new KekRing("k1", Map.of("k1", k1));
    byte[] wrapped = old.wrap("dek:x".getBytes(), new byte[] {9});
    KekRing rotated = new KekRing("k2", Map.of("k1", k1, "k2", k2));
    assertThat(rotated.unwrap("k1", "dek:x".getBytes(), wrapped)).containsExactly(9);
    assertThatThrownBy(() -> new KekRing("k3", Map.of("k1", k1))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new KekRing("k1", Map.of("k1", new byte[16]))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void issuedTokensPassTheServiceVerifierWithEveryContractClaim() throws Exception {
    KekRing kek = new KekRing("k1", Map.of("k1", key()));
    JoseKeyCrypto crypto = new JoseKeyCrypto(kek);
    KeyCrypto.NewKey k = crypto.generate();
    assertThat(k.publicJwk()).doesNotContain("\"d\"");
    KeyRow row = new KeyRow(k.kid(), k.publicJwk(), k.privateEnc(), k.kekId(), "active", Instant.now(), Instant.now(), null);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    UUID account = UUID.randomUUID();
    String token = crypto.sign(row, new TokenClaimsSpec(account.toString(), List.of("identity", "ledger"), "dexter-app",
        "sid-1", "user", List.of("USER"), List.of("pwd"), now.minusSeconds(5), false, "Asia/Kolkata", "PREMIUM",
        Duration.ofMinutes(10), "https://issuer.test", now, UUID.randomUUID().toString()));
    String jwks = crypto.jwks(List.of(row));
    JwtVerifier v = new JwtVerifier(JwtSettings.of("https://issuer.test", "ledger"),
        JwksCache.withDefaults(source(jwks), Clocks.systemUtc()), Clocks.systemUtc());
    AuthContext ctx = v.verify(token);
    assertThat(ctx.accountId()).isEqualTo(account);
    assertThat(ctx.sessionId()).isEqualTo("sid-1");
    assertThat(ctx.authTime()).isEqualTo(now.minusSeconds(5));
    assertThat(ctx.tier()).isEqualTo("PREMIUM");
    assertThat(ctx.mfaEnrolled()).isFalse();
    assertThat(jwks).doesNotContain("\"d\"");
    // the private key is only usable with the right KEK
    KekRing other = new KekRing("k1", Map.of("k1", key()));
    assertThatThrownBy(() -> new JoseKeyCrypto(other).sign(row, new TokenClaimsSpec("svc:ledger", List.of("catalog"),
        "ledger", null, "service", null, null, null, null, null, null, Duration.ofMinutes(5), "https://issuer.test", now,
        "j"))).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void serviceTokensHaveNoUserClaims() throws Exception {
    JoseKeyCrypto crypto = new JoseKeyCrypto(new KekRing("k1", Map.of("k1", key())));
    KeyCrypto.NewKey k = crypto.generate();
    KeyRow row = new KeyRow(k.kid(), k.publicJwk(), k.privateEnc(), k.kekId(), "active", Instant.now(), Instant.now(), null);
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String token = crypto.sign(row, new TokenClaimsSpec("svc:ledger", List.of("catalog"), "ledger", null, "service", null,
        null, null, null, null, null, Duration.ofMinutes(5), "https://issuer.test", now, "jti-1"));
    JwtVerifier v = new JwtVerifier(JwtSettings.of("https://issuer.test", "catalog"),
        JwksCache.withDefaults(source(crypto.jwks(List.of(row))), Clocks.systemUtc()), Clocks.systemUtc());
    AuthContext ctx = v.verify(token);
    assertThat(ctx.isService()).isTrue();
    assertThat(ctx.service()).isEqualTo("ledger");
  }
}
