package in.dexter.events;

import in.dexter.db.maintenance.BatchPurge;
import in.dexter.db.sql.Sql;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Deletes inbox rows older than 14 days (≫ 7-day topic retention), batched, as {@code <svc>_relay}. */
public final class InboxPurgeJob implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(InboxPurgeJob.class);

  private final BatchPurge purge;
  private final String table;
  private final int maxBatches;

  public InboxPurgeJob(JdbcClient relayJdbc, PlatformTransactionManager relayTx, String schema, int maxBatches) {
    this.purge = new BatchPurge(relayJdbc, relayTx);
    this.table = Sql.ident(schema) + ".inbox";
    this.maxBatches = maxBatches;
  }

  public BatchPurge.Result purgeNow() {
    BatchPurge.Result r = purge.purge(table, "received_at < now() - interval '14 days'", Map.of(), 5000, maxBatches,
        table + ":purge");
    if (r.deleted() > 0) {
      log.info("inbox purge {}: deleted {} rows in {} batches", table, r.deleted(), r.batches());
    }
    return r;
  }

  @Override
  public void run() {
    try {
      purgeNow();
    } catch (RuntimeException e) {
      log.warn("inbox purge {} failed: {}", table, e.toString());
    }
  }
}
