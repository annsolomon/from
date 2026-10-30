package in.dexter.contracts.events.planning;

import java.util.UUID;

/**
 * {@code planning.liability.changed} v1 (MASTER_PLAN P13): a liability was recorded, re-termed or closed. One
 * payload for all three — {@code status} and {@code version} say which state it is now, and the schedule is not
 * carried (a consumer that needs the instalments reads {@code /v1/liabilities/{id}/schedule}).
 *
 * <p>{@code emiPaise} is what the terms implied when the liability was recorded, from the shared EMI engine.
 */
public record LiabilityChangedV1(UUID liabilityId, String kind, String lender, long principalPaise, int aprBps,
    int tenureMonths, String startDate, long emiPaise, String status, long version, String changedAt) {
  public static final String TYPE = "planning.liability.changed";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.planning.liability.v1";
}
