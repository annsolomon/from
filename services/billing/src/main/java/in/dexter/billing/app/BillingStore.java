package in.dexter.billing.app;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plans, subscriptions, payments, webhook events and entitlement state (billing_app pool, RLS per account; the
 * plans are global and read-only). The two claims and {@link #paymentAccount} go through owner functions - the only
 * cross-account reach billing has (ADR-0022 §3).
 */
public interface BillingStore {

  record PlanRow(UUID id, String code, String name, long pricePaise, String interval, String entitlementsJson,
      boolean active, int sortOrder) {}

  /** A subscription with the plan fields every view needs (one join, no N+1). */
  record SubscriptionRow(UUID id, UUID accountId, UUID planId, String planCode, String planName, long planPricePaise,
      String interval, String status, Instant currentPeriodStart, Instant currentPeriodEnd, boolean cancelAtPeriodEnd,
      Instant canceledAt, Instant endedAt, String provider, String providerSubId, String billingState,
      Instant createdAt, Instant updatedAt, int version) {

    SubscriptionRow withStatus(String s, Instant at) {
      return new SubscriptionRow(id, accountId, planId, planCode, planName, planPricePaise, interval, s,
          currentPeriodStart, currentPeriodEnd, cancelAtPeriodEnd, canceledAt, endedAt, provider, providerSubId,
          billingState, createdAt, at, version + 1);
    }
  }

  record PaymentRow(UUID id, UUID accountId, UUID subscriptionId, UUID idemKey, long amountPaise, String currency,
      String status, String provider, String providerPaymentId, String paymentMethod, String failureCode,
      Instant createdAt, Instant updatedAt, Instant decidedAt, int version) {}

  record WebhookRow(String provider, String eventId, UUID accountId, String eventType, UUID paymentId,
      String payloadJson, Instant receivedAt) {}

  record EntitlementStateRow(UUID accountId, String tier, String entitlementsJson, long version, Instant updatedAt) {}

  record OverrideRow(String key, String valueJson, String reason, Instant expiresAt) {}

  /** One claimed row of a cross-account job: the account it belongs to and the row's key. */
  record Claim(UUID accountId, UUID key) {}

  // ------------------------------------------------------------------------------------------------ plans

  List<PlanRow> plans();

  Optional<PlanRow> planByCode(String code);

  Optional<PlanRow> plan(UUID id);

  // ------------------------------------------------------------------------------------------------ subscriptions

  /** The account's live subscription (at most one: subscriptions_one_live). */
  Optional<SubscriptionRow> live();

  /** The account's newest subscription of any status. */
  Optional<SubscriptionRow> latest();

  Optional<SubscriptionRow> subscription(UUID id);

  Optional<SubscriptionRow> lockSubscription(UUID id);

  /**
   * @throws in.dexter.core.error.DexterException {@code SUBSCRIPTION_EXISTS} when another live subscription won the
   *     race for the account (subscriptions_one_live)
   */
  void insertSubscription(SubscriptionRow s);

  void updateSubscription(SubscriptionRow s);

  // ------------------------------------------------------------------------------------------------ payments

  void insertPayment(PaymentRow p);

  Optional<PaymentRow> payment(UUID id);

  Optional<PaymentRow> lockPayment(UUID id);

  /** The checkout attempt made under this Idempotency-Key (Mode B recovery). */
  Optional<PaymentRow> paymentByKey(UUID idemKey);

  void updatePayment(PaymentRow p);

  /** Pre-authentication lookup for a webhook: the account a payment belongs to, whatever the tenant context. */
  Optional<UUID> paymentAccount(UUID paymentId);

  // ------------------------------------------------------------------------------------------------ webhooks

  /** False when this provider event id was already recorded (a replay). */
  boolean recordWebhook(WebhookRow w);

  void markWebhookProcessed(String provider, String eventId, String outcome, Instant at);

  // ------------------------------------------------------------------------------------------------ entitlements

  Optional<EntitlementStateRow> entitlementState();

  void saveEntitlementState(EntitlementStateRow s);

  List<OverrideRow> overrides();

  // ------------------------------------------------------------------------------------------------ jobs

  /** Mode B checkout keys whose lease ran out, re-leased for this sweeper (owner function, SKIP LOCKED). */
  List<Claim> claimStaleCheckouts(long leaseMs, int limit);

  /** Subscriptions whose period ended (past-due ones past their grace), for the period-end job. */
  List<Claim> claimPeriodEnds(long graceMs, int limit);
}
