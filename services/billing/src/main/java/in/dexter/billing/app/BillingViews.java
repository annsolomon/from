package in.dexter.billing.app;

import in.dexter.billing.app.BillingStore.PaymentRow;
import in.dexter.billing.app.BillingStore.PlanRow;
import in.dexter.billing.app.BillingStore.SubscriptionRow;
import in.dexter.billing.app.InvoiceStore.InvoiceRow;
import in.dexter.billing.app.InvoiceStore.LineRow;
import in.dexter.billing.domain.TaxInvoice;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The JSON shapes of billing's API (MASTER_PLAN 9: paise as {@code ...Paise}, ISO-8601 UTC instants). */
public final class BillingViews {
  private BillingViews() {}

  /** A plan with its price before GST, the GST and what a checkout charges. */
  public record PlanView(String code, String name, long pricePaise, long gstPaise, long totalPaise, String interval,
      Map<String, Object> entitlements) {
    static PlanView of(PlanRow p, Map<String, Object> entitlements) {
      long gst = TaxInvoice.gstOn(p.pricePaise());
      return new PlanView(p.code(), p.name(), p.pricePaise(), gst, p.pricePaise() + gst, p.interval(), entitlements);
    }
  }

  public record SubscriptionView(UUID id, String planCode, String planName, String interval, String status,
      Instant currentPeriodStart, Instant currentPeriodEnd, boolean cancelAtPeriodEnd, Instant canceledAt,
      Instant endedAt, String provider, Instant createdAt, Instant updatedAt, int version) {
    static SubscriptionView of(SubscriptionRow s) {
      return new SubscriptionView(s.id(), s.planCode(), s.planName(), s.interval(), s.status(),
          s.currentPeriodStart(), s.currentPeriodEnd(), s.cancelAtPeriodEnd(), s.canceledAt(), s.endedAt(),
          s.provider(), s.createdAt(), s.updatedAt(), s.version());
    }
  }

  public record PaymentView(UUID id, long amountPaise, String currency, String status, String failureCode,
      String providerPaymentId, Instant createdAt, Instant decidedAt) {
    static PaymentView of(PaymentRow p) {
      return new PaymentView(p.id(), p.amountPaise(), p.currency(), p.status(), p.failureCode(),
          p.providerPaymentId(), p.createdAt(), p.decidedAt());
    }
  }

  /**
   * A checkout's answer, which is also what its Idempotency-Key replays. {@code outcome} is succeeded (201), pending
   * (202: the provider will tell us by webhook) or failed (402, with {@code code} and {@code detail} as a problem
   * would carry them).
   */
  public record CheckoutView(String outcome, String code, String detail, SubscriptionView subscription,
      PaymentView payment, UUID invoiceId) {}

  public record InvoiceLineView(int lineNo, String kind, String description, Integer rateBps, long amountPaise) {
    static InvoiceLineView of(LineRow l) {
      return new InvoiceLineView(l.lineNo(), l.kind(), l.description(), l.rateBps(), l.amountPaise());
    }
  }

  public record InvoiceView(UUID id, String number, Instant issuedAt, Instant periodStart, Instant periodEnd,
      String supplierState, String placeOfSupply, String taxRegime, long subtotalPaise, long taxPaise,
      long totalPaise, String currency, String status, UUID subscriptionId, UUID paymentId,
      List<InvoiceLineView> lines) {
    static InvoiceView of(InvoiceRow i, List<LineRow> lines) {
      return new InvoiceView(i.id(), i.number(), i.issuedAt(), i.periodStart(), i.periodEnd(), i.supplierState(),
          i.placeOfSupply(), i.taxRegime(), i.subtotalPaise(), i.taxPaise(), i.totalPaise(), i.currency(),
          i.status(), i.subscriptionId(), i.paymentId(), lines.stream().map(InvoiceLineView::of).toList());
    }
  }

  public record OverrideView(String key, Object value, Instant expiresAt) {}

  /** What the account may use now: the tier, the effective map and the overrides in force. */
  public record EntitlementsView(String tier, String planCode, String subscriptionStatus,
      Map<String, Object> entitlements, List<OverrideView> overrides, long version) {}

  /** A webhook delivery's acknowledgement: processed, duplicate (a replay) or ignored (names no Dexter payment). */
  public record WebhookAck(String status, String eventId, String outcome) {}
}
