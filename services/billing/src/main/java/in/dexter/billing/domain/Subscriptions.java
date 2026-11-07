package in.dexter.billing.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

/**
 * Subscription states and the rules that move them without a payment (MASTER_PLAN P16).
 *
 * <pre>
 *   incomplete --payment succeeded--> active --period ends, cancel_at_period_end--> canceled
 *        |                              |  \--period ends, not canceling------> past_due --grace over--> expired
 *        \--payment failed---> incomplete_expired
 * </pre>
 *
 * Renewal charging is not built (BUILD_README §7): a period that ends without a cancel goes past due rather than
 * being charged again, keeps its entitlements through the grace period and then expires. Nothing is silently free.
 */
public final class Subscriptions {
  /** At most one per account (the partial unique index): a checkout in flight counts. */
  public static final Set<String> LIVE = Set.of("incomplete", "trialing", "active", "past_due");
  /** The plan's entitlements apply. A past-due subscription keeps them through its grace period. */
  public static final Set<String> ENTITLED = Set.of("trialing", "active", "past_due");
  /** A cancel (at period end) is accepted. */
  public static final Set<String> CANCELABLE = Set.of("trialing", "active", "past_due");

  private Subscriptions() {}

  /** The end of a period that starts at {@code start}: the same local time one month (or year) on, in {@code zone}. */
  public static Instant periodEnd(Instant start, String interval, ZoneId zone) {
    return switch (interval) {
      case "month" -> start.atZone(zone).plusMonths(1).toInstant();
      case "year" -> start.atZone(zone).plusYears(1).toInstant();
      default -> throw new IllegalArgumentException("unknown interval " + interval);
    };
  }

  /**
   * What the period-end job does with a subscription, if anything: canceled at the end of a period it was set to
   * cancel at, past due at the end of any other period, expired once a past-due grace period is over.
   */
  public static Optional<String> atPeriodEnd(String status, boolean cancelAtPeriodEnd, Instant periodEnd, Instant now,
      Duration grace) {
    if (periodEnd == null || now.isBefore(periodEnd)) {
      return Optional.empty();
    }
    return switch (status) {
      case "trialing", "active" -> Optional.of(cancelAtPeriodEnd ? "canceled" : "past_due");
      case "past_due" -> cancelAtPeriodEnd ? Optional.of("canceled")
          : now.isBefore(periodEnd.plus(grace)) ? Optional.empty() : Optional.of("expired");
      default -> Optional.empty();
    };
  }
}
