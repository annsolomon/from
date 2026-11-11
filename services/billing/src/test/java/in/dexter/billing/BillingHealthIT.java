package in.dexter.billing;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.billing.support.BillingIT;
import org.junit.jupiter.api.Test;

/** P00/P01 smoke on the real P16 context: health UP, X-Request-Id, coded ProblemDetail, Flyway applied as owner. */
class BillingHealthIT extends BillingIT {

  @Test
  void healthRequestIdProblemDetailAndMigrations() {
    Res health = send("GET", "/actuator/health", null, null, "X-Request-Id", "smoke-test-1");
    assertThat(health.status()).isEqualTo(200);
    assertThat(health.body()).contains("\"status\":\"UP\"");
    assertThat(health.header("X-Request-Id")).isEqualTo("smoke-test-1");

    Res nf = send("GET", "/v1/__nope", null, null);
    assertThat(nf.status()).as("unknown path without token: default-deny or 404").isIn(401, 404);
    assertThat(nf.body()).contains("\"requestId\":");

    // Flyway also records the schema-creation row, which is not of type SQL
    assertThat(count("SELECT count(1) FROM billing.flyway_schema_history WHERE success AND type = 'SQL'"))
        .isEqualTo(3); // P18 V202611111000__billing_privacy
    assertThat(one("SELECT tableowner FROM pg_tables WHERE schemaname = 'billing' AND tablename = 'subscriptions'"))
        .isEqualTo("billing_owner");
  }
}
