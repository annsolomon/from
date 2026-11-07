package in.dexter.billing.config;

import in.dexter.billing.app.CheckoutService;
import in.dexter.billing.app.SubscriptionService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Billing's two background jobs, on every instance (their claims are SKIP LOCKED, so instances share the work
 * without electing a leader): the Mode B checkout sweeper and the period-end job. Off unless
 * {@code dexter.billing.jobs.enabled=true}; tests call {@code recoverStale} and {@code endPeriods} directly.
 */
public final class BillingJobs implements SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(BillingJobs.class);
  private final CheckoutService checkout;
  private final SubscriptionService subscriptions;
  private final BillingProperties.Jobs settings;
  private ScheduledExecutorService executor;

  BillingJobs(CheckoutService checkout, SubscriptionService subscriptions, BillingProperties.Jobs settings) {
    this.checkout = checkout;
    this.subscriptions = subscriptions;
    this.settings = settings;
  }

  @Override
  public synchronized void start() {
    if (!settings.enabled() || executor != null) {
      return;
    }
    executor = Executors.newScheduledThreadPool(2, r -> {
      Thread t = new Thread(r, "billing-jobs");
      t.setDaemon(true);
      return t;
    });
    executor.scheduleWithFixedDelay(() -> {
      try {
        int n = checkout.recoverStale(settings.batch());
        if (n > 0) {
          log.info("CHECKOUTS_RECOVERED count={}", n);
        }
      } catch (RuntimeException e) {
        log.warn("CHECKOUT_SWEEP_ERROR error={}", e.toString());
      }
    }, settings.sweepEvery().toMillis(), settings.sweepEvery().toMillis(), TimeUnit.MILLISECONDS);
    executor.scheduleWithFixedDelay(() -> {
      try {
        int n = subscriptions.endPeriods(settings.batch());
        if (n > 0) {
          log.info("SUBSCRIPTION_PERIODS_ENDED count={}", n);
        }
      } catch (RuntimeException e) {
        log.warn("PERIOD_END_ERROR error={}", e.toString());
      }
    }, settings.periodEndEvery().toMillis(), settings.periodEndEvery().toMillis(), TimeUnit.MILLISECONDS);
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
