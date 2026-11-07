package in.dexter.billing.app;

import in.dexter.billing.app.BillingStore.Claim;
import in.dexter.billing.app.BillingStore.PaymentRow;
import in.dexter.billing.app.BillingStore.PlanRow;
import in.dexter.billing.app.BillingStore.SubscriptionRow;
import in.dexter.billing.app.BillingViews.CheckoutView;
import in.dexter.billing.app.BillingViews.PaymentView;
import in.dexter.billing.app.BillingViews.SubscriptionView;
import in.dexter.billing.app.PaymentProvider.ChargeRequest;
import in.dexter.billing.app.PaymentProvider.ChargeResult;
import in.dexter.billing.domain.BillingErrors;
import in.dexter.billing.domain.IndianStates;
import in.dexter.billing.domain.PaymentStatus;
import in.dexter.billing.domain.TaxInvoice;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /v1/subscriptions/checkout} (MASTER_PLAN P16): Idempotency-Key required, step-up required, and
 * <b>Mode B</b> (MASTER_PLAN 5.3) because the charge is an external side effect that no database rollback can undo.
 * Stripe's "atomic phases":
 *
 * <ol>
 *   <li><b>Phase 1</b>, one transaction: reserve the key with a lease, record the subscription as {@code incomplete}
 *       and the payment as {@code pending}, and the recovery point {@code payment_created}. Anything that fails here
 *       rolls the key back with the rows, so a retry starts clean (IdempotencyContract case 4).</li>
 *   <li><b>Phase 2</b>, no transaction: the provider call, idempotent on the same key, so a retry never charges
 *       twice.</li>
 *   <li><b>Phase 3</b>, one transaction: apply the outcome ({@link PaymentOutcomes}) and store the response the key
 *       will replay.</li>
 * </ol>
 *
 * Once phase 2 has started the key is never abandoned: if the process dies between the provider call and phase 3,
 * the key stays {@code in_progress} until its lease runs out, and then either a client retry takes it over (and asks
 * the provider again, safely) or {@link #recoverStale} finishes it from the provider's own record. That is the crash
 * MASTER_PLAN P16's named test kills the process for.
 */
public final class CheckoutService {
  private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
  public static final String PATH = "/v1/subscriptions/checkout";
  public static final String PAYMENT_CREATED = "payment_created";
  public static final String NOT_SUBMITTED = "not_submitted";
  /** The job's actor for the cross-account claim: no account, no token. */
  static final Tenant SYSTEM = new Tenant(new UUID(0, 0xB111_0000L), "SYSTEM", null);
  static final Pattern PAYMENT_METHOD = Pattern.compile("^pm_[a-z0-9_]{1,60}$");

  /** The checkout body (also the Idempotency-Key fingerprint). */
  public record Checkout(String plan, String paymentMethod, String billingState) {}

  private record Start(IdempotentResponse replay, PaymentRow payment, String description) {}

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final BillingStore store;
  private final InvoiceRead invoices;
  private final PaymentOutcomes outcomes;
  private final PaymentProvider provider;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final Duration lease;
  private final AtomicBoolean failNext = new AtomicBoolean();
  private final AtomicBoolean crashAfterCharge = new AtomicBoolean();

  /** The one read of the invoice store a response needs. */
  public interface InvoiceRead {
    Optional<UUID> invoiceOf(UUID paymentId);
  }

  public CheckoutService(TenantTx tx, IdempotencyEngine idempotency, BillingStore store, InvoiceRead invoices,
      PaymentOutcomes outcomes, PaymentProvider provider, ObjectMapper mapper, Clock clock, Duration lease) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.store = store;
    this.invoices = invoices;
    this.outcomes = outcomes;
    this.provider = provider;
    this.mapper = mapper;
    this.clock = clock;
    this.lease = lease;
  }

  /** Test hook (IdempotencyContract case 4): the next checkout fails in phase 1, after its business writes. */
  public void failNextCheckout() {
    failNext.set(true);
  }

  /**
   * Test hook (the named Mode B test): the next checkout dies after the provider answered and before phase 3 - the
   * moment a {@code kill -9} would leave a charged payment with nothing recorded about it.
   */
  public void crashAfterNextCharge() {
    crashAfterCharge.set(true);
  }

  public IdempotentResponse checkout(Tenant tenant, UUID key, Checkout cmd) {
    Checkout c = validate(cmd);
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, c, mapper);
    Start start = tx.write(tenant, () -> phase1(tenant, key, fingerprint, c));
    if (start.replay() != null) {
      return start.replay();
    }
    PaymentRow pay = start.payment();
    ChargeResult result;
    try {
      result = provider.charge(new ChargeRequest(key, pay.id(), tenant.accountId(), pay.amountPaise(),
          pay.currency(), pay.paymentMethod(), start.description()));
    } catch (PaymentProvider.Unavailable e) {
      // Whether the charge happened is unknown: the key stays in progress for a retry or the sweeper (never
      // abandoned once the call was made), and the client is told to retry the same key.
      log.warn("CHECKOUT_PROVIDER_UNAVAILABLE payment={} error={}", pay.id(), e.toString());
      throw new DexterException(BillingErrors.PROVIDER_UNAVAILABLE,
          "The payment provider did not answer; retry with the same Idempotency-Key", List.of(),
          Map.of("retryAfterSeconds", Math.max(1, lease.toSeconds())), e);
    }
    if (crashAfterCharge.getAndSet(false)) {
      throw new IllegalStateException("injected crash after the provider call, before phase 3");
    }
    return tx.write(tenant, () -> {
      outcomes.apply(tenant, pay.id(), result.status(), result.providerPaymentId(),
          result.providerSubscriptionId(), result.failureCode(), now());
      return idempotency.completeExternal(tenant, key, respond(pay.id()));
    });
  }

  private Start phase1(Tenant tenant, UUID key, IdempotentRequest fingerprint, Checkout c) {
    IdempotencyEngine.ExternalStart s = idempotency.beginExternal(tenant, key, fingerprint, lease);
    if (s instanceof IdempotencyEngine.Replay r) {
      return new Start(r.response(), null, null);
    }
    String recovery = ((IdempotencyEngine.Proceed) s).recoveryPoint();
    if (PAYMENT_CREATED.equals(recovery)) {
      // a previous attempt with this key died after phase 1: ask the provider again with the same key
      PaymentRow p = store.paymentByKey(key)
          .orElseThrow(() -> new IllegalStateException("recovery point without a payment for key " + key));
      SubscriptionRow sub = store.subscription(p.subscriptionId()).orElseThrow();
      return new Start(null, p, description(sub.planName(), sub.interval()));
    }
    PlanRow plan = store.planByCode(c.plan())
        .orElseThrow(() -> new DexterException(BillingErrors.PLAN_NOT_FOUND, "No plan " + c.plan()));
    if (!plan.active() || plan.pricePaise() == 0) {
      throw new DexterException(BillingErrors.PLAN_NOT_PURCHASABLE, plan.code() + " cannot be bought");
    }
    if (store.live().isPresent()) {
      throw new DexterException(BillingErrors.SUBSCRIPTION_EXISTS,
          "The account already has a subscription; cancel it first");
    }
    Instant now = now();
    SubscriptionRow sub = new SubscriptionRow(Ids.newId(), tenant.accountId(), plan.id(), plan.code(), plan.name(),
        plan.pricePaise(), plan.interval(), "incomplete", null, null, false, null, null, provider.name(), null,
        c.billingState(), now, now, 0);
    store.insertSubscription(sub);
    long amount = plan.pricePaise() + TaxInvoice.gstOn(plan.pricePaise());
    PaymentRow pay = new PaymentRow(Ids.newId(), tenant.accountId(), sub.id(), key, amount, "INR", "pending",
        provider.name(), null, c.paymentMethod(), null, now, now, null, 0);
    store.insertPayment(pay);
    idempotency.recordRecoveryPoint(tenant, key, PAYMENT_CREATED);
    if (failNext.getAndSet(false)) {
      throw new IllegalStateException("injected failure after the business write");
    }
    return new Start(null, pay, description(plan.name(), plan.interval()));
  }

  /**
   * The checkout response for a payment as it stands now (inside a transaction of its account): 201 when it
   * succeeded, 202 while the provider has not decided, 402 when it failed.
   */
  ApiResponse respond(UUID paymentId) {
    PaymentRow p = store.payment(paymentId).orElseThrow();
    SubscriptionRow s = store.subscription(p.subscriptionId()).orElseThrow();
    UUID invoice = invoices.invoiceOf(p.id()).orElse(null);
    PaymentStatus st = PaymentStatus.of(p.status());
    SubscriptionView sv = SubscriptionView.of(s);
    PaymentView pv = PaymentView.of(p);
    return switch (st) {
      case SUCCEEDED -> new ApiResponse(201, Map.of("Location", SubscriptionService.ME),
          new CheckoutView("succeeded", null, null, sv, pv, invoice));
      case PENDING -> new ApiResponse(202, Map.of("Location", SubscriptionService.ME),
          new CheckoutView("pending", null, "The provider has not decided yet; the subscription starts when it does",
              sv, pv, null));
      case FAILED -> {
        boolean never = NOT_SUBMITTED.equals(p.failureCode());
        BillingErrors code = never ? BillingErrors.PAYMENT_NOT_COMPLETED : BillingErrors.PAYMENT_DECLINED;
        String detail = never ? "The checkout did not reach the payment provider; nothing was charged"
            : "The payment was declined (" + p.failureCode() + "); nothing was charged";
        yield new ApiResponse(402, Map.of(), new CheckoutView("failed", code.code(), detail, sv, pv, null));
      }
    };
  }

  // ------------------------------------------------------------------------------------------------ recovery

  /**
   * The Mode B sweeper (MASTER_PLAN 5.3, P16): every checkout key whose lease ran out while still in progress is
   * claimed (and re-leased, so a client retry meanwhile gets 409), the provider is asked what became of the charge by
   * Dexter's own reference, the answer is applied exactly as a checkout would, and the key gets its final response.
   * A charge the provider never saw closes as failed ({@code not_submitted}). Returns how many keys it finished.
   */
  public int recoverStale(int limit) {
    List<Claim> claims = tx.write(SYSTEM, () -> store.claimStaleCheckouts(lease.toMillis(), limit));
    int done = 0;
    for (Claim c : claims) {
      try {
        if (recover(new Tenant(c.accountId(), "SYSTEM", null), c.key())) {
          done++;
        }
      } catch (RuntimeException e) {
        // the lease runs out again and the next pass retries it
        log.warn("CHECKOUT_RECOVERY_FAILED account={} key={} error={}", c.accountId(), c.key(), e.toString());
      }
    }
    return done;
  }

  private boolean recover(Tenant tenant, UUID key) {
    Optional<PaymentRow> pay = tx.read(tenant, () -> store.paymentByKey(key));
    if (pay.isEmpty()) {
      // phase 1 is one transaction, so a leased key always has its payment; anything else is not ours to finish
      tx.writeVoid(tenant, () -> idempotency.abandonExternal(tenant, key));
      return false;
    }
    Optional<ChargeResult> seen = provider.lookup(pay.get().id());
    tx.writeVoid(tenant, () -> {
      Instant now = now();
      if (seen.isPresent()) {
        ChargeResult r = seen.get();
        outcomes.apply(tenant, pay.get().id(), r.status(), r.providerPaymentId(), r.providerSubscriptionId(),
            r.failureCode(), now);
      } else {
        outcomes.apply(tenant, pay.get().id(), PaymentStatus.FAILED, null, null, NOT_SUBMITTED, now);
      }
      idempotency.completeExternal(tenant, key, respond(pay.get().id()));
    });
    log.info("CHECKOUT_RECOVERED account={} key={} provider_saw_it={}", tenant.accountId(), key, seen.isPresent());
    return true;
  }

  // ------------------------------------------------------------------------------------------------ validation

  private static Checkout validate(Checkout cmd) {
    if (cmd == null) {
      throw new ValidationException(List.of(new FieldError("body", "REQUIRED", "A checkout")));
    }
    List<FieldError> e = new ArrayList<>();
    String plan = cmd.plan() == null ? null : cmd.plan().strip().toUpperCase(Locale.ROOT);
    if (plan == null || plan.isEmpty()) {
      e.add(new FieldError("plan", "REQUIRED", "PREMIUM or VIP"));
    } else if (!plan.matches("[A-Z]{1,20}")) {
      e.add(new FieldError("plan", "INVALID", "PREMIUM or VIP"));
    }
    String pm = cmd.paymentMethod() == null ? null : cmd.paymentMethod().strip();
    if (pm == null || pm.isEmpty()) {
      e.add(new FieldError("paymentMethod", "REQUIRED", "A provider payment-method token (pm_...)"));
    } else if (!PAYMENT_METHOD.matcher(pm).matches()) {
      e.add(new FieldError("paymentMethod", "INVALID", "A provider payment-method token (pm_...), never card data"));
    }
    String state = cmd.billingState() == null ? null : cmd.billingState().strip().toUpperCase(Locale.ROOT);
    if (state == null || state.isEmpty()) {
      e.add(new FieldError("billingState", "REQUIRED", "The two-letter state code of the place of supply"));
    } else if (!IndianStates.valid(state)) {
      e.add(new FieldError("billingState", "INVALID", "A two-letter Indian state or union territory code"));
    }
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    return new Checkout(plan, pm, state);
  }

  private static String description(String planName, String interval) {
    return "Dexter " + planName + " (1 " + interval + ")";
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
