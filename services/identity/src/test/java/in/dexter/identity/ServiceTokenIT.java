package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.core.time.Clocks;
import in.dexter.identity.support.IdentityIT;
import in.dexter.security.AuthContext;
import in.dexter.security.client.ServiceTokenClient;
import in.dexter.security.jwt.HttpJwksSource;
import in.dexter.security.jwt.JwksCache;
import in.dexter.security.jwt.JwtSettings;
import in.dexter.security.jwt.JwtVerifier;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** MASTER_PLAN 7.2 service-to-service tokens: dexter-security's ServiceTokenClient against /oauth2/token. */
class ServiceTokenIT extends IdentityIT {

  ServiceTokenClient client(String id, String secret) {
    return new ServiceTokenClient(uri("/oauth2/token"), id, secret, ServiceTokenClient.defaultHttpClient(),
        JsonMapper.builder().build(), Clocks.systemUtc(), Duration.ofSeconds(60));
  }

  @Test
  void aServiceGetsAnAudienceBoundTokenThatTheTargetAccepts() {
    String token = client("ledger", "ledger-test-secret").tokenFor("catalog");
    JwtVerifier catalog = new JwtVerifier(JwtSettings.of(ISSUER, "catalog"),
        JwksCache.withDefaults(new HttpJwksSource(uri("/.well-known/jwks.json")), Clocks.systemUtc()), Clocks.systemUtc());
    AuthContext ctx = catalog.verify(token);
    assertThat(ctx.kind()).isEqualTo(AuthContext.Kind.SERVICE);
    assertThat(ctx.service()).isEqualTo("ledger");
    assertThat(ctx.audiences()).containsExactly("catalog");
    assertThat(ctx.expiresAt().getEpochSecond() - ctx.issuedAt().getEpochSecond()).isEqualTo(300);
    // a token for catalog is not a token for planning
    JwtVerifier planning = new JwtVerifier(JwtSettings.of(ISSUER, "planning"),
        JwksCache.withDefaults(new HttpJwksSource(uri("/.well-known/jwks.json")), Clocks.systemUtc()), Clocks.systemUtc());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> planning.verify(token))
        .hasMessageContaining("not for this service");
    // and a service token cannot act as a user on identity
    assertThat(get("/v1/me", bearer(token)).status()).isIn(401, 403);
  }

  @Test
  void badClientsAndRequestsAreRefused() {
    String basicOk = "Basic " + Base64.getEncoder().encodeToString("ledger:ledger-test-secret".getBytes());
    String basicBad = "Basic " + Base64.getEncoder().encodeToString("ledger:wrong".getBytes());
    String basicDisabled = "Basic " + Base64.getEncoder().encodeToString("catalog:".getBytes());
    String body = form(Map.of("grant_type", "client_credentials", "audience", "catalog"));
    String ct = "application/x-www-form-urlencoded";
    Res ok = send("POST", "/oauth2/token", body, ct, "Authorization", basicOk);
    assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
    assertThat(ok.json().get("token_type").asString()).isEqualTo("Bearer");
    assertThat(ok.json().get("expires_in").asLong()).isEqualTo(300);
    assertThat(ok.header("Cache-Control")).contains("no-store");

    assertThat(send("POST", "/oauth2/token", body, ct, "Authorization", basicBad).code()).isEqualTo("INVALID_CLIENT");
    assertThat(send("POST", "/oauth2/token", body, ct, "Authorization", basicDisabled).code())
        .isEqualTo("INVALID_CLIENT");
    assertThat(send("POST", "/oauth2/token", body, ct).status()).isEqualTo(401);
    assertThat(send("POST", "/oauth2/token", form(Map.of("grant_type", "password", "audience", "catalog")), ct,
        "Authorization", basicOk).code()).isEqualTo("UNSUPPORTED_GRANT_TYPE");
    assertThat(send("POST", "/oauth2/token", form(Map.of("grant_type", "client_credentials", "audience", "ledger")), ct,
        "Authorization", basicOk).code()).as("own audience not allowed").isEqualTo("INVALID_AUDIENCE");
  }
}
