package in.dexter.commerce.config;

import in.dexter.commerce.app.CounterFlusher;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The counter flusher (MASTER_PLAN 8.3), on every instance: each Redis drain is atomic, so instances share the
 * work without electing a leader. A final flush runs on shutdown so a clean stop loses nothing. Off unless
 * {@code dexter.commerce.jobs.enabled=true}; tests call {@link CounterFlusher#flush()} directly.
 */
public final class CommerceJobs implements SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(CommerceJobs.class);
  private final CounterFlusher flusher;
  private final CommerceProperties.Jobs settings;
  private ScheduledExecutorService executor;

  CommerceJobs(CounterFlusher flusher, CommerceProperties.Jobs settings) {
    this.flusher = flusher;
    this.settings = settings;
  }

  @Override
  public synchronized void start() {
    if (!settings.enabled() || executor != null) {
      return;
    }
    executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "commerce-counters");
      t.setDaemon(true);
      return t;
    });
    long every = settings.flushEvery().toMillis();
    executor.scheduleWithFixedDelay(this::flushOnce, every, every, TimeUnit.MILLISECONDS);
  }

  private void flushOnce() {
    try {
      int n = flusher.flush();
      if (n > 0) {
        log.debug("COUNTERS_FLUSHED deltas={}", n);
      }
    } catch (RuntimeException e) {
      log.warn("COUNTER_FLUSH_ERROR error={}", e.toString());
    }
  }

  @Override
  public synchronized void stop() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
      flushOnce();
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return executor != null;
  }
}
