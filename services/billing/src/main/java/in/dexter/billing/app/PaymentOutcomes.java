package in.dexter.billing.app;

import in.dexter.billing.app.BillingStore.PaymentRow;
import in.dexter.billing.app.BillingStore.SubscriptionRow;
import in.dexter.billing.app.InvoiceStore.InvoiceRow;
import in.dexter.billing.app.InvoiceStore.LineRow;
import in.dexter.billing.domain.InvoiceNumbers;
import in.dexter.billing.domain.PaymentStatus;
import in.dexter.billing.domain.Subscriptions;
import in.dexter.billing.domain.TaxInvoice;
import in.dexter.contracts.events.billing.SubscriptionChangedV1;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.events.OutboxWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place a payment's outcome is applied (ADR-0022 §2), whoever learned it: the provider's synchronous answer
 * at checkout, a webhook delivered in any order and any number of times, or the sweeper's lookup after a crash. It
 * runs inside the caller's write transaction and holds the payment row lock for its duration, so two outcomes for one
 * payment never interleave.
 *
 * <p>The rule that makes every source safe is {@link PaymentStatus#canMoveTo}: a payment is decided once. The first
 * decision activates the subscription (its first period, {@code billing.subscription.changed}), writes the invoice
 * with its GST lines, and refreshes the entitlements; every later report of the same outcome - or of an earlier
 * state, such as "created" after "succeeded" - is recorded as no change.
 */
public final class PaymentOutcomes {
  private static final Logger log = LoggerFactory.getLogger(PaymentOutcomes.class);
  static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  public enum Result { APPLIED, NO_CHANGE }

  private final BillingStore store;
  private final InvoiceStore invoices;
  private final OutboxWriter outbox;
  private final EntitlementService entitlements;
  private final String supplierState;

  public PaymentOutcomes(BillingStore store, InvoiceStore invoices, OutboxWriter outbox,
      EntitlementService entitlements, String supplierState) {
    this.store = store;
    this.invoices = invoices;
    this.outbox = outbox;
    this.entitlements = entitlements;
    this.supplierState = supplierState;
  }

  /**
   * Applies what the provider says about payment {@code paymentId}. Must run inside {@code TenantTx.write} for the
   * payment's account.
   */
  public Result apply(Tenant tenant, java.util.UUID paymentId, PaymentStatus next, String providerPaymentId,
      String providerSubscriptionId, String failureCode, Instant at) {
    PaymentRow p = store.lockPayment(paymentId)
        .orElseThrow(() -> new IllegalStateException("no payment " + paymentId));
    PaymentStatus current = PaymentStatus.of(p.status());
    boolean decide = current.canMoveTo(next);
    boolean learnsId = providerPaymentId != null && p.providerPaymentId() == null;
    if (!decide && !learnsId) {
      return Result.NO_CHANGE;
    }
    String status = decide ? next.db() : p.status();
    String failure = decide && next == PaymentStatus.FAILED ? failureCodeOf(failureCode) : p.failureCode();
    PaymentRow updated = new PaymentRow(p.id(), p.accountId(), p.subscriptionId(), p.idemKey(), p.amountPaise(),
        p.currency(), status, p.provider(), learnsId ? providerPaymentId : p.providerPaymentId(), p.paymentMethod(),
        failure, p.createdAt(), at, decide ? at : p.decidedAt(), p.version() + 1);
    store.updatePayment(updated);
    if (!decide) {
      return Result.NO_CHANGE; // only the provider's id was learned (e.g. "created" arriving after the decision)
    }
    SubscriptionRow s = store.lockSubscription(p.subscriptionId())
        .orElseThrow(() -> new IllegalStateException("no subscription " + p.subscriptionId()));
    if (next == PaymentStatus.SUCCEEDED) {
      succeeded(tenant, s, updated, providerSubscriptionId, at);
    } else if ("incomplete".equals(s.status())) {
      store.updateSubscription(ended(s, "incomplete_expired", at));
    }
    return Result.APPLIED;
  }

  private void succeeded(Tenant tenant, SubscriptionRow s, PaymentRow payment, String providerSubId, Instant at) {
    Instant start = at;
    Instant end = Subscriptions.periodEnd(start, s.interval(), IST);
    boolean startable = "incomplete".equals(s.status())
        || ("incomplete_expired".equals(s.status()) && store.live().isEmpty());
    if (startable) {
      SubscriptionRow active = new SubscriptionRow(s.id(), s.accountId(), s.planId(), s.planCode(), s.planName(),
          s.planPricePaise(), s.interval(), "active", start, end, false, null, null, s.provider(),
          providerSubId != null ? providerSubId : s.providerSubId(), s.billingState(), s.createdAt(), at,
          s.version() + 1);
      store.updateSubscription(active);
      publish(active, at);
    } else {
      // The money was taken for a checkout that was closed meanwhile (the sweeper expired it, and another
      // subscription is live now). The invoice is still owed; the refund is not built (BUILD_README §7).
      log.warn("PAYMENT_SUCCEEDED_ON_CLOSED_SUBSCRIPTION payment={} subscription={} status={}", payment.id(), s.id(),
          s.status());
    }
    invoice(s, payment, start, end, at);
    entitlements.refresh(tenant, at);
  }

  private void invoice(SubscriptionRow s, PaymentRow payment, Instant start, Instant end, Instant at) {
    if (invoices.byPayment(payment.id()).isPresent()) {
      return; // unreachable while a payment is decided once; the UNIQUE (payment_id) is the second guard
    }
    TaxInvoice.Result tax = TaxInvoice.of("Dexter " + s.planName() + " - 1 " + s.interval(), s.planPricePaise(),
        supplierState, s.billingState());
    if (tax.totalPaise() != payment.amountPaise()) {
      throw new IllegalStateException("invoice total " + tax.totalPaise() + " != charged " + payment.amountPaise());
    }
    java.util.UUID id = Ids.newId();
    String number = InvoiceNumbers.format(at, invoices.nextSerial());
    List<LineRow> lines = new ArrayList<>();
    for (TaxInvoice.Line l : tax.lines()) {
      lines.add(new LineRow(id, l.lineNo(), l.kind().name().toLowerCase(Locale.ROOT), l.description(), l.rateBps(),
          l.amountPaise()));
    }
    invoices.insert(new InvoiceRow(id, s.accountId(), s.id(), payment.id(), number, at, start, end, supplierState,
        s.billingState(), tax.regime(), tax.subtotalPaise(), tax.taxPaise(), tax.totalPaise(), payment.currency(),
        "paid"), lines);
  }

  /** A status change without a payment (the period-end job, cancel), with its event and entitlement refresh. */
  void transition(Tenant tenant, SubscriptionRow updated, Instant at) {
    store.updateSubscription(updated);
    publish(updated, at);
    entitlements.refresh(tenant, at);
  }

  void publish(SubscriptionRow s, Instant at) {
    outbox.append(BillingEvents.SUBSCRIPTION_CHANGED, s.accountId(), s.id(), s.version(),
        new SubscriptionChangedV1(s.id(), s.planCode(), s.status(), s.currentPeriodStart().toString(),
            s.currentPeriodEnd().toString(), s.cancelAtPeriodEnd(), s.version(), at.toString()));
  }

  static SubscriptionRow ended(SubscriptionRow s, String status, Instant at) {
    return new SubscriptionRow(s.id(), s.accountId(), s.planId(), s.planCode(), s.planName(), s.planPricePaise(),
        s.interval(), status, s.currentPeriodStart(), s.currentPeriodEnd(), s.cancelAtPeriodEnd(), s.canceledAt(),
        at, s.provider(), s.providerSubId(), s.billingState(), s.createdAt(), at, s.version() + 1);
  }

  private static String failureCodeOf(String code) {
    if (code == null || !code.matches("[a-z0-9_]{1,40}")) {
      return "declined";
    }
    return code;
  }
}
