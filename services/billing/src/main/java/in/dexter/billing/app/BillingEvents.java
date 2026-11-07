package in.dexter.billing.app;

import in.dexter.contracts.events.billing.EntitlementsChangedV1;
import in.dexter.contracts.events.billing.SubscriptionChangedV1;
import in.dexter.events.EventType;
import java.util.List;

/** Billing's event types (contracts/events/billing.*.v1.json). */
public final class BillingEvents {
  /** A subscription started, was set to cancel, went past due, was canceled or expired. */
  public static final EventType<SubscriptionChangedV1> SUBSCRIPTION_CHANGED = EventType.of("billing", "subscription",
      "changed", SubscriptionChangedV1.VERSION, SubscriptionChangedV1.class);
  /** An account's tier or effective entitlements changed; identity puts the tier in the next token. */
  public static final EventType<EntitlementsChangedV1> ENTITLEMENTS_CHANGED = EventType.of("billing", "entitlements",
      "changed", EntitlementsChangedV1.VERSION, EntitlementsChangedV1.class);

  /** The aggregates billing publishes (topics dexter.billing.&lt;aggregate&gt;.v1, created by the relay). */
  public static final List<String> AGGREGATES = List.of("subscription", "entitlements");

  private BillingEvents() {}
}
