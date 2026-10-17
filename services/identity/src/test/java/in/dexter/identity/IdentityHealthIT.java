package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.identity.support.IdentityIT;
import org.junit.jupiter.api.Test;

/** P00/P01 smoke on the real P04 context: health UP, X-Request-Id, coded ProblemDetail, Flyway applied as owner. */
class IdentityHealthIT extends IdentityIT {

  @Test
  void healthRequestIdProblemDetailAndMigrations() {
    Res health = get("/actuator/health", "X-Request-Id", "smoke-test-1");
    assertThat(health.status()).isEqualTo(200);
    assertThat(health.body()).contains("\"status\":\"UP\"");
    assertThat(health.header("X-Request-Id")).isEqualTo("smoke-test-1");

    Res nf = get("/v1/__nope");
    assertThat(nf.status()).as("unknown path without token: default-deny or 404").isIn(401, 404);
    assertThat(nf.body()).contains("\"requestId\":");

    assertThat(ADMIN.sql("SELECT count(1) FROM identity.flyway_schema_history WHERE success AND type = 'SQL'")
        .query(Long.class).single()).isEqualTo(2);
    assertThat(ADMIN.sql("SELECT tableowner FROM pg_tables WHERE schemaname = 'identity' AND tablename = 'accounts'")
        .query(String.class).single()).isEqualTo("identity_owner");
  }

  @Test
  void signingKeysWereBootstrapped() {
    assertThat(ADMIN.sql("SELECT status FROM identity.signing_keys ORDER BY status").query(String.class).list())
        .contains("active", "next");
    Res jwks = get("/.well-known/jwks.json");
    assertThat(jwks.status()).isEqualTo(200);
    assertThat(jwks.json().get("keys").size()).isGreaterThanOrEqualTo(2);
    assertThat(jwks.body()).doesNotContain("\"d\"");
    assertThat(jwks.header("Cache-Control")).contains("max-age=300");
  }
}
