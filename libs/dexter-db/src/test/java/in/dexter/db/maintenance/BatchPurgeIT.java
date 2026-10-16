package in.dexter.db.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import in.dexter.db.testsupport.PgTestSupport;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** Batched retention delete as the relay role: only expired rows go, and the lock makes instances take turns. */
class BatchPurgeIT {
  static HikariDataSource relay;
  static BatchPurge purge;

  @BeforeAll
  static void setUp() {
    PgTestSupport.pg();
    // Inbox rows (no RLS; relay has SELECT, DELETE): 12,345 expired + 7 fresh.
    PgTestSupport.exec("postgres", """
        INSERT INTO demo.inbox (consumer, event_id, received_at)
        SELECT 'purge-it', gen_random_uuid(), now() - interval '20 days' FROM generate_series(1, 12345);
        INSERT INTO demo.inbox (consumer, event_id, received_at)
        SELECT 'purge-it', gen_random_uuid(), now() FROM generate_series(1, 7);
        """);
    relay = PgTestSupport.pool("demo_relay", 2, "purge", false);
    purge = new BatchPurge(JdbcClient.create(relay), new JdbcTransactionManager(relay));
  }

  @AfterAll
  static void tearDown() {
    relay.close();
  }

  static long left() {
    return JdbcClient.create(relay).sql("SELECT count(1) FROM demo.inbox WHERE consumer = 'purge-it'")
        .query(Long.class).single();
  }

  @Test
  void deletesOnlyExpiredRowsInBatches() {
    BatchPurge.Result r = purge.purge("demo.inbox", "consumer = :c AND received_at < now() - interval '14 days'",
        Map.of("c", "purge-it"), 5000, 100, "it:inbox-purge");
    assertThat(r.skipped()).isFalse();
    assertThat(r.deleted()).isEqualTo(12_345);
    assertThat(r.batches()).isEqualTo(3);
    assertThat(left()).isEqualTo(7);
  }

  @Test
  void skipsWhileAnotherInstanceHoldsTheLock() throws Exception {
    try (Connection c = PgTestSupport.conn("demo_relay"); Statement s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("SELECT pg_advisory_xact_lock(hashtext('it:held'))");
      BatchPurge.Result r = purge.purge("demo.inbox", "received_at < now() - interval '14 days'", Map.of(),
          100, 1, "it:held");
      assertThat(r.skipped()).isTrue();
      c.rollback();
    }
  }

  @Test
  void rejectsBadIdentifiers() {
    assertThatThrownBy(() -> purge.purge("demo.inbox; DROP TABLE x", "true", Map.of(), 10, 1, "k"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> purge.purge("inbox", "true", Map.of(), 10, 1, "k"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
