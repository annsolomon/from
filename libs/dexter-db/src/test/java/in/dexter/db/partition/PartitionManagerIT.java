package in.dexter.db.partition;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import in.dexter.db.partition.PartitionManager.Grain;
import in.dexter.db.testsupport.PgTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PartitionManagerIT {
  static HikariDataSource relay;
  static JdbcClient jdbc;
  static PartitionManager pm;
  static JdbcTransactionManager tm;

  @BeforeAll
  static void setUp() {
    PgTestSupport.exec("demo_owner", """
        CREATE TABLE demo.events (id uuid NOT NULL, created_at timestamptz NOT NULL, published_at timestamptz,
          PRIMARY KEY (id, created_at)) PARTITION BY RANGE (created_at);
        GRANT SELECT, INSERT, UPDATE ON demo.events TO demo_relay;
        CREATE TABLE demo.monthly (id uuid NOT NULL, created_at timestamptz NOT NULL, PRIMARY KEY (id, created_at))
          PARTITION BY RANGE (created_at);
        CREATE TABLE demo.locked_t (id uuid NOT NULL, created_at timestamptz NOT NULL, PRIMARY KEY (id, created_at))
          PARTITION BY RANGE (created_at);
        """);
    relay = PgTestSupport.pool("demo_relay", 3, "relay", false);
    jdbc = JdbcClient.create(relay);
    tm = new JdbcTransactionManager(relay);
    pm = new PartitionManager(jdbc, tm, "demo");
  }

  @AfterAll
  static void tearDown() {
    relay.close();
  }

  static List<String> partitions(String parent) {
    return jdbc.sql("""
        SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
        WHERE i.inhparent = CAST(:p AS regclass) ORDER BY 1""").param("p", "demo." + parent)
        .query(String.class).list();
  }

  @Test
  void createsAheadIdempotentlyAndDropsWithGuard() {
    Instant anchor = Instant.parse("2026-03-10T15:00:00Z");
    assertThat(pm.createAhead("events", Grain.DAY, 3, anchor)).isEqualTo(new PartitionManager.Result(false, 4));
    assertThat(pm.createAhead("events", Grain.DAY, 3, anchor).changed()).isZero();
    assertThat(partitions("events")).containsExactly(
        "events_p20260310", "events_p20260311", "events_p20260312", "events_p20260313");

    jdbc.sql("INSERT INTO demo.events (id, created_at) VALUES (gen_random_uuid(), '2026-03-10T01:00:00Z')").update();
    jdbc.sql("INSERT INTO demo.events (id, created_at, published_at) VALUES (gen_random_uuid(), '2026-03-11T01:00:00Z', now())").update();

    // cutoff after both days: the 10th still has an unpublished row, so only the 11th goes
    assertThat(pm.dropOlderThan("events", Instant.parse("2026-03-12T00:00:00Z"), "published_at").changed()).isEqualTo(1);
    assertThat(partitions("events")).containsExactly("events_p20260310", "events_p20260312", "events_p20260313");

    jdbc.sql("UPDATE demo.events SET published_at = now() WHERE published_at IS NULL").update();
    assertThat(pm.dropOlderThan("events", Instant.parse("2026-03-12T00:00:00Z"), "published_at").changed()).isEqualTo(1);
    assertThat(partitions("events")).containsExactly("events_p20260312", "events_p20260313");
  }

  @Test
  void monthlyGrain() {
    assertThat(pm.createAhead("monthly", Grain.MONTH, 2, Instant.parse("2026-12-31T23:59:00Z")).changed()).isEqualTo(3);
    assertThat(partitions("monthly")).containsExactly("monthly_p202612", "monthly_p202701", "monthly_p202702");
  }

  @Test
  void onlyOneInstanceRunsAtATime() throws Exception {
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> holder = pool.submit(() -> new TransactionTemplate(tm).executeWithoutResult(s -> {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('partitions:demo.locked_t'))").query().singleRow();
        held.countDown();
        try {
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }));
      assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(pm.createAhead("locked_t", Grain.DAY, 1).skipped()).isTrue();
      release.countDown();
      holder.get(10, TimeUnit.SECONDS);
      assertThat(pm.createAhead("locked_t", Grain.DAY, 1).skipped()).isFalse();
    } finally {
      pool.shutdownNow();
    }
  }
}
