package in.dexter.contracts.events.billing;

import java.util.UUID;

/**
 * {@code billing.subscription.changed} v1 (MASTER_PLAN P16): a subscription became active, was set to cancel at the
 * end of its period, went past due, was canceled or expired. One payload for every transition - {@code status},
 * {@code cancelAtPeriodEnd} and {@code version} say which state it is in now. A checkout that is still waiting on
 * its payment ({@code incomplete}) or whose payment failed ({@code incomplete_expired}) publishes nothing: no other
 * service has anything to do with a subscription that never started.
 */
public record SubscriptionChangedV1(UUID subscriptionId, String planCode, String status, String currentPeriodStart,
    String currentPeriodEnd, boolean cancelAtPeriodEnd, long version, String changedAt) {
  public static final String TYPE = "billing.subscription.changed";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.billing.subscription.v1";
}
