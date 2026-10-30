package in.dexter.planning;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.planning.support.PlanningIT;
import org.junit.jupiter.api.Test;

/** P00/P01 smoke on the real P13 context: health UP, X-Request-Id, coded ProblemDetail, Flyway applied as owner. */
class PlanningHealthIT extends PlanningIT {
  @Test
  void contextLoadsAndHealthIsUp() {
    Res health = send("GET", "/actuator/health", null, null, "X-Request-Id", "smoke-test-1");
    assertThat(health.status()).isEqualTo(200);
    assertThat(health.body()).contains("\"status\":\"UP\"");
    assertThat(health.header("X-Request-Id")).isEqualTo("smoke-test-1");

    Res nf = send("GET", "/v1/__nope", null, null);
    assertThat(nf.status()).isIn(401, 404);
    assertThat(nf.body()).contains("\"requestId\":");

    assertThat(ADMIN.sql("SELECT count(1) FROM planning.flyway_schema_history WHERE success AND type = 'SQL'")
        .query(Long.class).single()).as("the platform migration and the core one").isEqualTo(2);
    assertThat(ADMIN.sql("SELECT tableowner FROM pg_tables WHERE schemaname = 'planning' AND tablename = 'budgets'")
        .query(String.class).single()).isEqualTo("planning_owner");
  }
}
