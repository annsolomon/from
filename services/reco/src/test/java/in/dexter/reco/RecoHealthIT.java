package in.dexter.reco;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.reco.support.RecoIT;
import org.junit.jupiter.api.Test;

/** P00/P01 smoke on the real P14 context: health UP, X-Request-Id, coded ProblemDetail, Flyway applied as owner. */
class RecoHealthIT extends RecoIT {
  @Test
  void contextLoadsAndHealthIsUp() {
    Res health = send("GET", "/actuator/health", null, null, "X-Request-Id", "smoke-test-1");
    assertThat(health.status()).isEqualTo(200);
    assertThat(health.body()).contains("\"status\":\"UP\"");
    assertThat(health.header("X-Request-Id")).isEqualTo("smoke-test-1");

    Res nf = send("GET", "/v1/__nope", null, null);
    assertThat(nf.status()).isIn(401, 404);
    assertThat(nf.body()).contains("\"requestId\":");

    assertThat(ADMIN.sql("SELECT count(1) FROM reco.flyway_schema_history WHERE success AND type = 'SQL'")
        .query(Long.class).single()).as("the platform migration and the core one").isEqualTo(2);
    assertThat(ADMIN.sql("SELECT tableowner FROM pg_tables WHERE schemaname = 'reco'"
            + " AND tablename = 'spend_profile'").query(String.class).single()).isEqualTo("reco_owner");
  }

  @Test
  void theHistoryTableIsPartitionedWithPartitionsReady() {
    assertThat(ADMIN.sql("SELECT count(1) FROM pg_partitioned_table p JOIN pg_class c ON c.oid = p.partrelid"
            + " JOIN pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE n.nspname = 'reco' AND c.relname = 'recommendation_history'")
        .query(Long.class).single()).as("partitioned, as MASTER_PLAN P14 asks").isEqualTo(1);
    assertThat(ADMIN.sql("SELECT count(1) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE n.nspname = 'reco' AND c.relname LIKE 'recommendation_history_p%'")
        .query(Long.class).single()).as("the migration created this month's and two ahead")
        .isGreaterThanOrEqualTo(3);
  }
}
