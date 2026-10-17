package in.dexter.ledger.config;

import in.dexter.ledger.app.ImportProcessor;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Polls for import jobs (P08): every {@code pollInterval} it claims and runs jobs until none is waiting. One thread
 * per instance; instances share the work through the claim's {@code FOR UPDATE SKIP LOCKED}.
 */
public final class ImportWorker implements SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(ImportWorker.class);
  private final ImportProcessor processor;
  private final boolean enabled;
  private final Duration pollInterval;
  private ScheduledExecutorService executor;

  public ImportWorker(ImportProcessor processor, boolean enabled, Duration pollInterval) {
    this.processor = processor;
    this.enabled = enabled;
    this.pollInterval = pollInterval;
  }

  /** Runs every waiting job now (tests; the scheduled loop does the same). */
  public int drain() {
    int n = 0;
    while (processor.runOnce().isPresent()) {
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
      Thread t = new Thread(r, "ledger-import-worker");
      t.setDaemon(true);
      return t;
    });
    executor.scheduleWithFixedDelay(() -> {
      try {
        drain();
      } catch (RuntimeException e) {
        log.warn("IMPORT_WORKER_ERROR error={}", e.toString());
      }
    }, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
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
