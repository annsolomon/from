package in.dexter.db.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import in.dexter.db.testsupport.PgTestSupport;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** P01: PlanAssert catches a Seq Scan, an avoidable Sort, and a missing index. */
class PlanAssertIT {
  static HikariDataSource ds;
  static JdbcClient jdbc;

  @BeforeAll
  static void setUp() {
    PgTestSupport.exec("demo_owner", """
        CREATE TABLE demo.plan_t (id bigint PRIMARY KEY, grp integer NOT NULL, v bigint NOT NULL);
        CREATE INDEX plan_t_grp ON demo.plan_t (grp, id DESC);
        GRANT SELECT ON demo.plan_t TO demo_app;
        INSERT INTO demo.plan_t SELECT g, g % 1000, g FROM generate_series(1, 100000) g;
        ANALYZE demo.plan_t;
        """);
    ds = PgTestSupport.pool("demo_app", 1, "plan", false);
    jdbc = JdbcClient.create(ds);
  }

  @AfterAll
  static void tearDown() {
    ds.close();
  }

  @Test
  void seqScanOnUnindexedPredicateIsCaught() {
    assertThatThrownBy(() -> PlanAssert.query(jdbc, "SELECT id FROM demo.plan_t WHERE v = :v", Map.of("v", 5L))
        .forbidSeqScanOn("plan_t").verify())
        .isInstanceOf(AssertionError.class).hasMessageContaining("Seq Scan on plan_t");
  }

  @Test
  void sortIsCaught() {
    assertThatThrownBy(() -> PlanAssert.query(jdbc,
            "SELECT id FROM demo.plan_t WHERE grp = :g ORDER BY v DESC LIMIT 10", Map.of("g", 7))
        .forbidSort().verify())
        .isInstanceOf(AssertionError.class).hasMessageContaining("Sort");
  }

  @Test
  void indexedKeysetQueryPasses() {
    String plan = PlanAssert.query(jdbc,
            "SELECT id FROM demo.plan_t WHERE grp = :g AND id < :after ORDER BY id DESC LIMIT 26",
            Map.of("g", 7, "after", 90000L))
        .forbidSeqScanOn("plan_t").forbidSort().requireIndex("plan_t_grp").verify();
    assertThat(plan).contains("plan_t_grp");
  }

  @Test
  void missingRequiredIndexIsCaught() {
    assertThatThrownBy(() -> PlanAssert.query(jdbc, "SELECT id FROM demo.plan_t WHERE id = :id", Map.of("id", 1L))
        .requireIndex("plan_t_grp").verify())
        .isInstanceOf(AssertionError.class).hasMessageContaining("not used");
  }
}
