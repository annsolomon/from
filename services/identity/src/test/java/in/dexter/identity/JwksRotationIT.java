package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.core.time.Clocks;
import in.dexter.identity.app.SigningKeyService;
import in.dexter.identity.support.IdentityIT;
import in.dexter.security.AuthContext;
import in.dexter.security.jwt.HttpJwksSource;
import in.dexter.security.jwt.JwksCache;
import in.dexter.security.jwt.JwtSettings;
import in.dexter.security.jwt.JwtVerifier;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * MASTER_PLAN P04: tokens are verified by the same dexter-security verifier every service runs, fetching identity's
 * JWKS over HTTP; after a rotation, tokens signed with the old key stay valid (the retired key is still published)
 * and new tokens use the new key.
 */
class JwksRotationIT extends IdentityIT {
  @Autowired
  SigningKeyService keys;

  JwtVerifier serviceVerifier(String audience) {
    JwksCache cache = JwksCache.withDefaults(new HttpJwksSource(uri("/.well-known/jwks.json")), Clocks.systemUtc());
    return new JwtVerifier(JwtSettings.of(ISSUER, audience), cache, Clocks.systemUtc());
  }

  @Test
  void aServiceVerifiesUserTokensAndTheContractClaims() {
    String email = uniqueEmail("svc-verify");
    JsonNode t = signUpAndLogin(email);
    AuthContext ctx = serviceVerifier("ledger").verify(t.get("accessToken").asString());
    assertThat(ctx.kind()).isEqualTo(AuthContext.Kind.USER);
    assertThat(ctx.accountId()).isEqualTo(accountIdOf(email));
    assertThat(ctx.sessionId()).isEqualTo(t.get("sessionId").asString());
    assertThat(ctx.clientId()).isEqualTo("dexter-app");
    assertThat(ctx.roles()).containsExactly("USER");
    assertThat(ctx.amr()).containsExactly("pwd");
    assertThat(ctx.authTime()).isNotNull();
    assertThat(ctx.tz().getId()).isEqualTo("Asia/Kolkata");
    assertThat(ctx.tier()).isEqualTo("FREE");
    assertThat(ctx.expiresAt().getEpochSecond() - ctx.issuedAt().getEpochSecond()).isEqualTo(600);
    assertThat(ctx.audiences()).contains("identity", "ledger", "catalog", "audit");
  }

  @Test
  void tokensFromTheOldKeyStayValidAfterRotation() {
    JsonNode before = signUpAndLogin(uniqueEmail("rotate"));
    String oldKid = keys.current().active().kid();
    JwtVerifier ledger = serviceVerifier("ledger");
    ledger.verify(before.get("accessToken").asString());

    SigningKeyService.Snapshot after = keys.rotateNow();
    assertThat(after.active().kid()).isNotEqualTo(oldKid);
    Set<String> published = new HashSet<>();
    get("/.well-known/jwks.json").json().get("keys").forEach(k -> published.add(k.get("kid").asString()));
    assertThat(published).contains(oldKid, after.active().kid());
    assertThat(ADMIN.sql("SELECT status FROM identity.signing_keys WHERE kid = :k").param("k", oldKid)
        .query(String.class).single()).isEqualTo("retired");

    // the old token still works everywhere (identity itself and another service)
    assertThat(get("/v1/me", bearer(before.get("accessToken").asString())).status()).isEqualTo(200);
    assertThat(ledger.verify(before.get("accessToken").asString()).accountId()).isNotNull();
    // a refresh now yields a token signed by the new key, also accepted
    Res refreshed = post("/v1/auth/token/refresh", java.util.Map.of("refreshToken",
        before.get("refreshToken").asString()));
    String newToken = refreshed.json().get("accessToken").asString();
    assertThat(kidOf(newToken)).isEqualTo(after.active().kid());
    assertThat(serviceVerifier("planning").verify(newToken).accountId()).isNotNull();
  }

  static String kidOf(String jwt) {
    String header = new String(java.util.Base64.getUrlDecoder().decode(jwt.substring(0, jwt.indexOf('.'))),
        java.nio.charset.StandardCharsets.UTF_8);
    return JSON.readTree(header).get("kid").asString();
  }
}
