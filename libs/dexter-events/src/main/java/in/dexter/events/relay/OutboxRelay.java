package in.dexter.events.relay;

import in.dexter.db.sql.Sql;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The outbox relay (MASTER_PLAN 6.3). One active instance per service:
 * <ul>
 *   <li>leader election with a session {@code pg_try_advisory_lock(hashtext('<svc>.outbox'))} on a
 *       dedicated connection (use a non-pooled DataSource as {@code <svc>_relay}); others stand by and
 *       retry every {@code standbyRetry};</li>
 *   <li>wakes on {@code LISTEN <svc>_outbox_new} (the insert trigger's NOTIFY) or every
 *       {@code pollInterval} (200ms);</li>
 *   <li>claims up to {@code batchSize} (500) rows {@code ORDER BY created_at, id FOR UPDATE SKIP LOCKED},
 *       sends them with the idempotent producer, waits for the acks, then sets {@code published_at}
 *       in the same transaction. A crash before commit republishes the batch (at-least-once;
 *       consumers dedupe through the inbox);</li>
 *   <li>maintenance on the leader every {@code maintenanceInterval}: creates partitions ahead, drops
 *       fully-published partitions older than {@code retention}, alerts (ERROR log +
 *       {@link #partitionsHealthy()}) when tomorrow+1's partition is missing, and runs extra tasks
 *       (inbox/idempotency purge).</li>
 * </ul>
 */
public final class OutboxRelay implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  /** Relay settings. {@link #defaults(String)} gives the MASTER_PLAN values. */
  public record Settings(String schema, int batchSize, Duration pollInterval, Duration standbyRetry,
      int partitionsAhead, Duration retention, Duration maintenanceInterval, Duration sendTimeout) {
    public Settings {
      Sql.ident(schema);
      if (batchSize < 1 || batchSize > 5000) {
        throw new IllegalArgumentException("batchSize 1..5000");
      }
      if (pollInterval.toMillis() < 1 || standbyRetry.toMillis() < 1 || sendTimeout.toMillis() < 1) {
        throw new IllegalArgumentException("intervals must be >= 1ms");
      }
      if (partitionsAhead < 2) {
        throw new IllegalArgumentException("partitionsAhead must be >= 2");
      }
    }

    public static Settings defaults(String schema) {
      return new Settings(schema, 500, Duration.ofMillis(200), Duration.ofSeconds(1), 3, Duration.ofDays(3),
          Duration.ofHours(1), Duration.ofSeconds(20));
    }

    public Settings withBatchSize(int n) {
      return new Settings(schema, n, pollInterval, standbyRetry, partitionsAhead, retention, maintenanceInterval,
          sendTimeout);
    }

    public Settings withStandbyRetry(Duration d) {
      return new Settings(schema, batchSize, pollInterval, d, partitionsAhead, retention, maintenanceInterval,
          sendTimeout);
    }
  }

  /** Test seam: called after each send of a batch, before the acks are awaited and the batch committed. */
  public interface Hooks {
    Hooks NONE = new Hooks() {};

    default void afterSend(int sentInBatch) {}
  }

  record Row(UUID id, OffsetDateTime createdAt, String topic, String key, byte[] payload, List<Header> headers) {}

  private final DataSource dataSource;
  private final Producer<String, byte[]> producer;
  private final Settings s;
  private final Hooks hooks;
  private final List<Runnable> maintenanceTasks;
  private final String channel;
  private final String claimSql;
  private final String markSql;
  private final String failSql;
  private final String lockKey;

  private volatile boolean running;
  private volatile boolean leader;
  private volatile boolean partitionsHealthy = true;
  private volatile long lastMaintenanceNanos;
  private final AtomicLong published = new AtomicLong();
  private Thread thread;

  public OutboxRelay(DataSource relayDataSource, Producer<String, byte[]> producer, Settings settings, Hooks hooks,
      List<Runnable> maintenanceTasks) {
    this.dataSource = Objects.requireNonNull(relayDataSource, "relayDataSource");
    this.producer = Objects.requireNonNull(producer, "producer");
    this.s = Objects.requireNonNull(settings, "settings");
    this.hooks = hooks == null ? Hooks.NONE : hooks;
    this.maintenanceTasks = List.copyOf(maintenanceTasks == null ? List.of() : maintenanceTasks);
    String t = s.schema() + ".outbox";
    this.channel = s.schema() + "_outbox_new";
    this.lockKey = t;
    // headers: key=value pairs from the jsonb object (keys are library-owned: ce_id, ce_type, ...)
    this.claimSql = "SELECT o.id, o.created_at, o.topic, o.message_key, o.payload::text AS payload,"
        + " (SELECT coalesce(array_agg(h.key || '=' || h.value ORDER BY h.key), '{}') FROM jsonb_each_text(o.headers) h) AS hdrs"
        + " FROM " + t + " o WHERE o.published_at IS NULL ORDER BY o.created_at, o.id LIMIT ? FOR UPDATE OF o SKIP LOCKED";
    this.markSql = "UPDATE " + t + " SET published_at = now(), attempts = attempts + 1"
        + " WHERE id = ANY(?) AND created_at >= ? AND created_at <= ?";
    this.failSql = "UPDATE " + t + " SET attempts = attempts + 1 WHERE id = ANY(?) AND created_at >= ? AND created_at <= ?";
  }

  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    thread = Thread.ofVirtual().name("outbox-relay-" + s.schema()).start(this::loop);
  }

  /** Stops the loop and releases leadership (the lock is released when the connection closes). */
  @Override
  public void close() {
    Thread t;
    synchronized (this) {
      running = false;
      t = thread;
      thread = null;
    }
    if (t != null) {
      t.interrupt(); // wakes a standby/backoff sleep; the loop sees running=false and exits
      try {
        t.join(Duration.ofSeconds(10).toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public boolean isLeader() {
    return leader;
  }

  public boolean isRunning() {
    return running;
  }

  /** False when the partition for today+2 (UTC) was missing at the last maintenance: outbox inserts will fail soon. */
  public boolean partitionsHealthy() {
    return partitionsHealthy;
  }

  public long publishedCount() {
    return published.get();
  }

  // ---------------------------------------------------------------------------------------- loop

  private void loop() {
    while (running) {
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(true);
        if (!tryLock(c)) {
          sleep(s.standbyRetry());
          continue;
        }
        try {
          leader = true;
          log.info("outbox relay {}: leader", s.schema());
          exec(c, "LISTEN " + channel);
          lastMaintenanceNanos = 0;
          lead(c);
        } finally {
          leader = false;
          releaseQuietly(c);
        }
      } catch (SQLException | RuntimeException e) {
        leader = false;
        if (running) {
          log.warn("outbox relay {}: {} (retrying)", s.schema(), e.toString());
          sleep(s.standbyRetry());
        }
      }
    }
  }

  private void lead(Connection c) throws SQLException {
    PGConnection pg = c.unwrap(PGConnection.class);
    int waitMs = (int) Math.max(1, s.pollInterval().toMillis());
    while (running) {
      if (lastMaintenanceNanos == 0 || System.nanoTime() - lastMaintenanceNanos >= s.maintenanceInterval().toNanos()) {
        maintain(c);
        lastMaintenanceNanos = System.nanoTime();
      }
      int n;
      do {
        n = relayBatch(c);
      } while (running && n >= s.batchSize());
      pg.getNotifications(waitMs); // returns early on NOTIFY, else after the poll interval
    }
  }

  /** One batch: claim, send, await acks, mark. Returns rows claimed. */
  int relayBatch(Connection c) throws SQLException {
    c.setAutoCommit(false);
    boolean done = false;
    try {
      List<Row> rows = claim(c);
      if (rows.isEmpty()) {
        c.commit();
        done = true;
        return 0;
      }
      List<Future<RecordMetadata>> sent = new ArrayList<>(rows.size());
      for (int i = 0; i < rows.size(); i++) {
        Row r = rows.get(i);
        sent.add(producer.send(new ProducerRecord<>(r.topic(), null, r.key(), r.payload(), r.headers())));
        hooks.afterSend(i + 1);
      }
      producer.flush();
      List<Row> ok = new ArrayList<>(rows.size());
      List<Row> failed = new ArrayList<>();
      long deadline = System.nanoTime() + s.sendTimeout().toNanos();
      for (int i = 0; i < rows.size(); i++) {
        try {
          sent.get(i).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
          ok.add(rows.get(i));
        } catch (ExecutionException | TimeoutException e) {
          failed.add(rows.get(i));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while awaiting acks", e);
        }
      }
      update(c, markSql, ok);
      update(c, failSql, failed);
      c.commit();
      done = true;
      published.addAndGet(ok.size());
      if (!failed.isEmpty()) {
        log.warn("outbox relay {}: {} of {} sends failed; they stay unpublished and are retried", s.schema(),
            failed.size(), rows.size());
      }
      return rows.size();
    } finally {
      if (!done) {
        try {
          c.rollback();
        } catch (SQLException ignored) {
          // the connection is broken; the outer loop reconnects
        }
      }
      c.setAutoCommit(true);
    }
  }

  private List<Row> claim(Connection c) throws SQLException {
    List<Row> rows = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(claimSql)) {
      ps.setInt(1, s.batchSize());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          List<Header> headers = new ArrayList<>();
          Array a = rs.getArray("hdrs");
          if (a != null) {
            for (String kv : (String[]) a.getArray()) {
              int eq = kv.indexOf('=');
              if (eq > 0) {
                headers.add(new RecordHeader(kv.substring(0, eq), kv.substring(eq + 1).getBytes(StandardCharsets.UTF_8)));
              }
            }
          }
          rows.add(new Row(rs.getObject("id", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
              rs.getString("topic"), rs.getString("message_key"),
              rs.getString("payload").getBytes(StandardCharsets.UTF_8), headers));
        }
      }
    }
    return rows;
  }

  private static void update(Connection c, String sql, List<Row> rows) throws SQLException {
    if (rows.isEmpty()) {
      return;
    }
    OffsetDateTime min = rows.getFirst().createdAt();
    OffsetDateTime max = min;
    UUID[] ids = new UUID[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      Row r = rows.get(i);
      ids[i] = r.id();
      if (r.createdAt().isBefore(min)) {
        min = r.createdAt();
      }
      if (r.createdAt().isAfter(max)) {
        max = r.createdAt();
      }
    }
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setArray(1, c.createArrayOf("uuid", ids));
      ps.setObject(2, min);
      ps.setObject(3, max);
      ps.executeUpdate();
    }
  }

  // ------------------------------------------------------------------------------- maintenance

  private void maintain(Connection c) {
    try {
      c.setAutoCommit(false);
      try (PreparedStatement lock = c.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtext(?))")) {
        lock.setString(1, "partitions:" + lockKey);
        try (ResultSet rs = lock.executeQuery()) {
          rs.next();
          if (rs.getBoolean(1)) {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + s.schema() + ".create_partitions_ahead('outbox', 'day', ?)")) {
              ps.setInt(1, s.partitionsAhead());
              ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT " + s.schema()
                + ".drop_partitions_older_than('outbox', now() - ? * interval '1 second', 'published_at')")) {
              ps.setLong(1, s.retention().toSeconds());
              ps.execute();
            }
          }
        }
      }
      c.commit();
      partitionsHealthy = checkPartitionsAhead(c);
    } catch (SQLException | RuntimeException e) {
      try {
        c.rollback();
      } catch (SQLException ignored) {
        // reconnect handles it
      }
      partitionsHealthy = safeCheck(c);
      log.error("OUTBOX_MAINTENANCE_FAILED {}: {}", s.schema(), e.toString());
    } finally {
      try {
        c.setAutoCommit(true);
      } catch (SQLException ignored) {
        // reconnect handles it
      }
    }
    if (!partitionsHealthy) {
      log.error("OUTBOX_PARTITIONS_NOT_AHEAD {}: no outbox partition for today+2 (UTC); inserts will fail when the"
          + " current partitions run out", s.schema());
    }
    for (Runnable task : maintenanceTasks) {
      try {
        task.run();
      } catch (RuntimeException e) {
        log.warn("outbox relay {}: maintenance task failed: {}", s.schema(), e.toString());
      }
    }
  }

  private boolean safeCheck(Connection c) {
    try {
      return checkPartitionsAhead(c);
    } catch (SQLException | RuntimeException e) {
      return false;
    }
  }

  private boolean checkPartitionsAhead(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(format('%I.%I', ?::text,"
        + " 'outbox_p' || to_char((now() AT TIME ZONE 'UTC') + interval '2 days', 'YYYYMMDD'))) IS NOT NULL")) {
      ps.setString(1, s.schema());
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  // ------------------------------------------------------------------------------------- utils

  private boolean tryLock(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
      ps.setString(1, lockKey);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  private void releaseQuietly(Connection c) {
    try {
      if (!c.isClosed()) {
        if (!c.getAutoCommit()) {
          c.rollback();
          c.setAutoCommit(true);
        }
        exec(c, "UNLISTEN *");
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
          ps.setString(1, lockKey);
          ps.execute();
        }
      }
    } catch (SQLException ignored) {
      // closing the connection releases the session lock anyway
    }
  }

  private static void exec(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  private static void sleep(Duration d) {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
