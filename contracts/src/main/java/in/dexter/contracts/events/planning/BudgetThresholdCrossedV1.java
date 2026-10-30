package in.dexter.contracts.events.planning;

import java.util.UUID;

/**
 * {@code planning.budget.threshold_crossed} v1 (MASTER_PLAN P13): a budget's spend reached one of its alert
 * thresholds in a period, and this is the first time that threshold has been reached in that period — the row in
 * {@code planning.budget_alerts_sent} is written in the same transaction as this event, so the notification service
 * sees each threshold at most once per period.
 *
 * <p>{@code usedBps} is the spend against the limit in basis points (10000 = exactly on the limit), so a consumer
 * needs no division. The envelope's accountId is the budget's owner and its aggregateVersion is the budget's version.
 */
public record BudgetThresholdCrossedV1(UUID budgetId, String name, String period, String categoryPath,
    String periodStart, int threshold, long limitPaise, long spentPaise, int usedBps, String crossedAt) {
  public static final String TYPE = "planning.budget.threshold_crossed";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.planning.budget.v1";
}
