package in.dexter.audit.config;

import in.dexter.audit.app.AuditLogService;
import in.dexter.audit.app.NotificationService;
import in.dexter.audit.app.PrivacyService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The notification worker, the privacy saga's job and the anchor job, on every instance: the first two claim with
 * SKIP LOCKED, the anchor insert is idempotent per seq. Off unless {@code dexter.audit.jobs.enabled=true}; tests
 * call the services directly.
 */
public final class AuditJobs implements SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(AuditJobs.class);
  private final NotificationService notifications;
  private final PrivacyService privacy;
  private final AuditLogService audit;
  private final AuditProperties.Jobs settings;
  private ScheduledExecutorService executor;

  AuditJobs(NotificationService notifications, PrivacyService privacy, AuditLogService audit,
      AuditProperties.Jobs settings) {
    this.notifications = notifications;
    this.privacy = privacy;
    this.audit = audit;
    this.settings = settings;
  }

  @Override
  public synchronized void start() {
    if (!settings.enabled() || executor != null) {
      return;
    }
    executor = Executors.newScheduledThreadPool(3, r -> {
      Thread t = new Thread(r, "audit-jobs");
      t.setDaemon(true);
      return t;
    });
    every(settings.notifyEvery().toMillis(), "NOTIFY", () -> notifications.deliverDue(50));
    every(settings.sagaEvery().toMillis(), "PRIVACY", () -> privacy.tick(20));
    every(settings.anchorEvery().toMillis(), "ANCHOR", audit::anchorNow);
  }

  private void every(long ms, String name, Runnable job) {
    executor.scheduleWithFixedDelay(() -> {
      try {
        job.run();
      } catch (RuntimeException e) {
        log.warn("AUDIT_JOB_ERROR job={} error={}", name, e.toString());
      }
    }, ms, ms, TimeUnit.MILLISECONDS);
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
