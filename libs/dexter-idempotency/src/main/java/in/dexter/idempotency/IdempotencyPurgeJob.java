package in.dexter.idempotency;

import in.dexter.db.maintenance.BatchPurge;
import in.dexter.db.sql.Sql;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Deletes keys older than 24h (MASTER_PLAN 5.2) in batches of 5000 by ctid, under an advisory lock.
 * Runs as {@code <svc>_relay}: its RLS policies only expose expired keys, so a bug here can never
 * delete a live key. Index: {@code idempotency_keys_created_brin}.
 */
public final class IdempotencyPurgeJob implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);
  static final int BATCH = 5000;

  private final BatchPurge purge;
  private final String table;
  private final int maxBatches;

  public IdempotencyPurgeJob(JdbcClient relayJdbc, PlatformTransactionManager relayTx, String schema, int maxBatches) {
    this.purge = new BatchPurge(relayJdbc, relayTx);
    this.table = Sql.ident(schema) + ".idempotency_keys";
    this.maxBatches = maxBatches;
  }

  public BatchPurge.Result purgeNow() {
    BatchPurge.Result r = purge.purge(table, "created_at < now() - interval '24 hours'", Map.of(), BATCH, maxBatches,
        table + ":purge");
    if (r.deleted() > 0) {
      log.info("idempotency purge {}: deleted {} keys in {} batches", table, r.deleted(), r.batches());
    }
    return r;
  }

  @Override
  public void run() {
    try {
      purgeNow();
    } catch (RuntimeException e) {
      log.warn("idempotency purge {} failed: {}", table, e.toString());
    }
  }
}
