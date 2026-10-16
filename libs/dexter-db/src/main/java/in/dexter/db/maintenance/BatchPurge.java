package in.dexter.db.maintenance;

import in.dexter.db.sql.Sql;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Batched retention deletes (MASTER_PLAN 5.2, 6.4): {@code DELETE ... WHERE ctid = ANY(ARRAY(SELECT
 * ctid ... LIMIT n))}, one short transaction per batch, each under {@code
 * pg_try_advisory_xact_lock(hashtext(lockKey))} so only one instance purges a table at a time and no
 * batch holds locks for long. Run it as the {@code <svc>_relay} role, whose policies only expose
 * expired rows.
 */
public final class BatchPurge {
  private static final Pattern QUALIFIED = Pattern.compile("[a-z_][a-z0-9_]{0,62}\\.[a-z_][a-z0-9_]{0,62}");

  /** Outcome: {@code skipped} when another instance held the lock on the first batch. */
  public record Result(boolean skipped, long deleted, int batches) {}

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;

  public BatchPurge(JdbcClient jdbc, PlatformTransactionManager txManager) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.tx = new TransactionTemplate(Objects.requireNonNull(txManager, "txManager"));
  }

  /**
   * @param qualifiedTable {@code schema.table}, validated
   * @param where          a trusted, library-owned predicate (never user input), with named params
   * @param batchSize      rows per batch (plan: 5000)
   * @param maxBatches     upper bound per call so one run cannot monopolise the database
   */
  public Result purge(String qualifiedTable, String where, Map<String, ?> params, int batchSize,
      int maxBatches, String lockKey) {
    if (!QUALIFIED.matcher(qualifiedTable).matches()) {
      throw new IllegalArgumentException("bad table: " + qualifiedTable);
    }
    Sql.ident(qualifiedTable.substring(0, qualifiedTable.indexOf('.')));
    if (batchSize < 1 || batchSize > 50_000 || maxBatches < 1) {
      throw new IllegalArgumentException("bad batch settings");
    }
    Objects.requireNonNull(where, "where");
    Objects.requireNonNull(lockKey, "lockKey");
    String sql = "DELETE FROM " + qualifiedTable + " WHERE ctid = ANY(ARRAY(SELECT ctid FROM " + qualifiedTable
        + " WHERE " + where + " LIMIT :__batch))";
    long total = 0;
    int batches = 0;
    while (batches < maxBatches) {
      Integer n = tx.execute(status -> {
        Boolean got = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext(:k))").param("k", lockKey)
            .query(Boolean.class).single();
        if (!Boolean.TRUE.equals(got)) {
          return -1;
        }
        return jdbc.sql(sql).params(params == null ? Map.of() : params).param("__batch", batchSize).update();
      });
      if (n == null || n < 0) {
        return new Result(batches == 0, total, batches);
      }
      batches++;
      total += n;
      if (n < batchSize) {
        break;
      }
    }
    return new Result(false, total, batches);
  }
}
