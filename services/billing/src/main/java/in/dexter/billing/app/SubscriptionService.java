package in.dexter.billing.app;

import in.dexter.billing.app.BillingStore.Claim;
import in.dexter.billing.app.BillingStore.SubscriptionRow;
import in.dexter.billing.app.BillingViews.SubscriptionView;
import in.dexter.billing.domain.BillingErrors;
import in.dexter.billing.domain.Subscriptions;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * The account's subscription (MASTER_PLAN P16): {@code GET /v1/subscriptions/me}, {@code POST
 * /v1/subscriptions/me:cancel} (at the end of the period; step-up; an Idempotency-Key is optional, and a repeated
 * cancel changes nothing anyway), and the period-end job that ends what the clock ends.
 */
public final class SubscriptionService {
  private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
  public static final String ME = "/v1/subscriptions/me";
  public static final String CANCEL = "/v1/subscriptions/me:cancel";

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final BillingStore store;
  private final PaymentOutcomes outcomes;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final Duration pastDueGrace;

  public SubscriptionService(TenantTx tx, IdempotencyEngine idempotency, BillingStore store, PaymentOutcomes outcomes,
      ObjectMapper mapper, Clock clock, Duration pastDueGrace) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.store = store;
    this.outcomes = outcomes;
    this.mapper = mapper;
    this.clock = clock;
    this.pastDueGrace = pastDueGrace;
  }

  /** The live subscription, else the newest one; 404 for an account that never had one. */
  public SubscriptionView me(Tenant tenant) {
    return tx.read(tenant, () -> store.live().or(store::latest).map(SubscriptionView::of)
        .orElseThrow(() -> new DexterException(BillingErrors.SUBSCRIPTION_NOT_FOUND, "No subscription yet")));
  }

  /** Cancel at the end of the current period; the plan's entitlements last until then. */
  public IdempotentResponse cancel(Tenant tenant, @Nullable UUID key) {
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", CANCEL, null, mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      SubscriptionRow live = store.live()
          .orElseThrow(() -> new DexterException(BillingErrors.SUBSCRIPTION_NOT_FOUND, "No live subscription"));
      SubscriptionRow s = store.lockSubscription(live.id()).orElseThrow();
      if (!Subscriptions.CANCELABLE.contains(s.status())) {
        throw new DexterException(BillingErrors.SUBSCRIPTION_NOT_CANCELABLE,
            "A " + s.status() + " subscription cannot be canceled");
      }
      if (s.cancelAtPeriodEnd()) {
        return ApiResponse.of(200, SubscriptionView.of(s));
      }
      Instant now = now();
      SubscriptionRow canceling = new SubscriptionRow(s.id(), s.accountId(), s.planId(), s.planCode(), s.planName(),
          s.planPricePaise(), s.interval(), s.status(), s.currentPeriodStart(), s.currentPeriodEnd(), true, now,
          null, s.provider(), s.providerSubId(), s.billingState(), s.createdAt(), now, s.version() + 1);
      outcomes.transition(tenant, canceling, now);
      return new ApiResponse(200, Map.of("ETag", "\"" + canceling.version() + "\""), SubscriptionView.of(canceling));
    }));
  }

  /**
   * One pass of the period-end job: every subscription whose period ended moves on (canceled if it was set to
   * cancel, else past due; a past-due one expires after its grace). Each is re-locked and re-checked in its own
   * account's transaction, so a row two instances both claimed is decided once. Returns how many moved.
   */
  public int endPeriods(int limit) {
    List<Claim> claims = tx.write(CheckoutService.SYSTEM, () -> store.claimPeriodEnds(pastDueGrace.toMillis(), limit));
    int moved = 0;
    for (Claim c : claims) {
      Tenant t = new Tenant(c.accountId(), "SYSTEM", null);
      try {
        Boolean m = tx.write(t, () -> {
          Optional<SubscriptionRow> row = store.lockSubscription(c.key());
          if (row.isEmpty()) {
            return false;
          }
          SubscriptionRow s = row.get();
          Instant now = now();
          Optional<String> next = Subscriptions.atPeriodEnd(s.status(), s.cancelAtPeriodEnd(), s.currentPeriodEnd(),
              now, pastDueGrace);
          if (next.isEmpty()) {
            return false;
          }
          SubscriptionRow updated = "past_due".equals(next.get()) ? s.withStatus("past_due", now)
              : PaymentOutcomes.ended(s, next.get(), now);
          outcomes.transition(t, updated, now);
          return true;
        });
        if (Boolean.TRUE.equals(m)) {
          moved++;
        }
      } catch (RuntimeException e) {
        log.warn("PERIOD_END_FAILED account={} subscription={} error={}", c.accountId(), c.key(), e.toString());
      }
    }
    return moved;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
