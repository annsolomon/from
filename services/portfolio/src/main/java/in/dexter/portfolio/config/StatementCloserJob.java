package in.dexter.portfolio.config;

import in.dexter.core.tenant.Tenant;
import in.dexter.portfolio.app.TransactionService;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Generates the statements of ended cycles every {@code interval} (all tenants, through the owner's
 * {@code close_ended_statements}; instances share the work with SKIP LOCKED).
 */
public final class StatementCloserJob implements SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(StatementCloserJob.class);
  /** The job's actor: no account, no token. */
  static final Tenant SYSTEM = new Tenant(new UUID(0, 0x5157_E000L), "SYSTEM", null);
  private final TransactionService txns;
  private final boolean enabled;
  private final Duration interval;
  private final int batch;
  private ScheduledExecutorService executor;

  public StatementCloserJob(TransactionService txns, boolean enabled, Duration interval, int batch) {
    this.txns = txns;
    this.enabled = enabled;
    this.interval = interval;
    this.batch = batch;
  }

  /** Closes every ended cycle now (tests; the schedule does the same). */
  public int runOnce() {
    return txns.closeEndedStatements(SYSTEM, batch);
  }

  @Override
  public synchronized void start() {
    if (!enabled || executor != null) {
      return;
    }
    executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "portfolio-statement-closer");
      t.setDaemon(true);
      return t;
    });
    executor.scheduleWithFixedDelay(() -> {
      try {
        int n = runOnce();
        if (n > 0) {
          log.info("STATEMENTS_GENERATED count={}", n);
        }
      } catch (RuntimeException e) {
        log.warn("STATEMENT_CLOSER_ERROR error={}", e.toString());
      }
    }, 30_000, interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return executor != null;
  }
}
