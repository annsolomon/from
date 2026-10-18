package in.dexter.catalog.config;

import in.dexter.catalog.app.IngestionRunner;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The ingestion worker (P10): every {@code pollInterval} it claims and executes crawl runs until none waits, and every
 * {@code scheduleInterval} it queues runs for due scheduled sources. One thread per instance; instances share the
 * work through {@code FOR UPDATE SKIP LOCKED} claims.
 */
public final class IngestionWorker implements SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);
  private final IngestionRunner runner;
  private final boolean enabled;
  private final Duration pollInterval;
  private final Duration scheduleInterval;
  private ScheduledExecutorService executor;

  public IngestionWorker(IngestionRunner runner, boolean enabled, Duration pollInterval, Duration scheduleInterval) {
    this.runner = runner;
    this.enabled = enabled;
    this.pollInterval = pollInterval;
    this.scheduleInterval = scheduleInterval;
  }

  /** Runs every waiting run now (tests; the scheduled loop does the same). */
  public int drain() {
    int n = 0;
    while (runner.runOnce().isPresent()) {
      n++;
    }
    return n;
  }

  @Override
  public synchronized void start() {
    if (!enabled || executor != null) {
      return;
    }
    executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "catalog-ingestion-worker");
      t.setDaemon(true);
      return t;
    });
    executor.scheduleWithFixedDelay(() -> {
      try {
        drain();
      } catch (RuntimeException e) {
        log.warn("INGESTION_WORKER_ERROR error={}", e.toString());
      }
    }, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    executor.scheduleWithFixedDelay(() -> {
      try {
        runner.scheduleDue();
      } catch (RuntimeException e) {
        log.warn("INGESTION_SCHEDULER_ERROR error={}", e.toString());
      }
    }, scheduleInterval.toMillis(), scheduleInterval.toMillis(), TimeUnit.MILLISECONDS);
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
