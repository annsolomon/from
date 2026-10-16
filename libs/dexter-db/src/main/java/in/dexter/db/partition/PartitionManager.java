package in.dexter.db.partition;

import in.dexter.db.sql.Sql;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates partitions ahead and drops old ones (MASTER_PLAN 3.6) through the SECURITY DEFINER
 * functions of {@code sql-templates/10-partitions.sql}. Each call runs in its own transaction under
 * {@code pg_try_advisory_xact_lock}, so across all instances only one does the work; the others get
 * {@link Result#skipped()}. Run it as the {@code <svc>_relay} role from a scheduled job.
 */
public final class PartitionManager {
  public enum Grain { DAY, MONTH }

  /** Outcome of one call: {@code skipped} when another instance held the lock. */
  public record Result(boolean skipped, int changed) {}

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final String schema;

  public PartitionManager(JdbcClient jdbc, PlatformTransactionManager txManager, String schema) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.tx = new TransactionTemplate(txManager);
    this.schema = Sql.ident(schema);
  }

  public Result createAhead(String table, Grain grain, int ahead) {
    return createAhead(table, grain, ahead, null);
  }

  /** @param anchor the "today" to start from (null = database now()); for tests. */
  public Result createAhead(String table, Grain grain, int ahead, Instant anchor) {
    String t = Sql.ident(table);
    return locked("partitions:" + schema + "." + t, () -> jdbc
        .sql("SELECT " + schema + ".create_partitions_ahead(:t, :g, :n, COALESCE(CAST(:a AS timestamptz), now()))")
        .param("t", t).param("g", grain.name().toLowerCase(java.util.Locale.ROOT)).param("n", ahead)
        .param("a", Sql.ts(anchor))
        .query(Integer.class).single());
  }

  /**
   * Drops partitions entirely older than {@code cutoff}. With {@code guardColumn} (for example
   * {@code published_at}) a partition that still has a row with that column NULL is kept.
   */
  public Result dropOlderThan(String table, Instant cutoff, String guardColumn) {
    String t = Sql.ident(table);
    String g = guardColumn == null ? null : Sql.ident(guardColumn);
    return locked("partitions:" + schema + "." + t, () -> jdbc
        .sql("SELECT " + schema + ".drop_partitions_older_than(:t, :c, :g)")
        .param("t", t).param("c", Sql.ts(cutoff)).param("g", g)
        .query(Integer.class).single());
  }

  private Result locked(String lockKey, java.util.function.Supplier<Integer> work) {
    return tx.execute(status -> {
      Boolean got = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext(:k))").param("k", lockKey)
          .query(Boolean.class).single();
      if (!Boolean.TRUE.equals(got)) {
        return new Result(true, 0);
      }
      Integer n = work.get();
      return new Result(false, n == null ? 0 : n);
    });
  }
}
