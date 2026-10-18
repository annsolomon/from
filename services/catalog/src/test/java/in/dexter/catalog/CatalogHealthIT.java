package in.dexter.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.catalog.support.CatalogIT;
import org.junit.jupiter.api.Test;

/** P00/P01 smoke on the real P09 context: health UP, X-Request-Id, coded ProblemDetail, Flyway applied as owner. */
class CatalogHealthIT extends CatalogIT {

  @Test
  void healthRequestIdProblemDetailAndMigrations() {
    Res health = send("GET", "/actuator/health", null, null, "X-Request-Id", "smoke-test-1");
    assertThat(health.status()).isEqualTo(200);
    assertThat(health.body()).contains("\"status\":\"UP\"");
    assertThat(health.header("X-Request-Id")).isEqualTo("smoke-test-1");
    Res nf = send("GET", "/v1/__nope", null, null);
    assertThat(nf.status()).isIn(401, 404);
    assertThat(nf.body()).contains("\"requestId\":");
    assertThat(ADMIN.sql("SELECT count(1) FROM catalog.flyway_schema_history WHERE success AND type = 'SQL'")
        .query(Long.class).single()).isEqualTo(3);
    assertThat(ADMIN.sql("SELECT tableowner FROM pg_tables WHERE schemaname = 'catalog' AND tablename = 'card_products'")
        .query(String.class).single()).isEqualTo("catalog_owner");
  }
}
