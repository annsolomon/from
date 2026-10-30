package in.dexter.planning.app;

import in.dexter.contracts.events.planning.BudgetThresholdCrossedV1;
import in.dexter.contracts.events.planning.LiabilityChangedV1;
import in.dexter.events.EventType;
import java.util.List;

/** Planning's event types (contracts/events/planning.*.v1.json). */
public final class PlanningEvents {
  /** A threshold reached for the first time in a period; the notification service consumes it (MASTER_PLAN P13). */
  public static final EventType<BudgetThresholdCrossedV1> BUDGET_THRESHOLD_CROSSED = EventType.of("planning",
      "budget", "threshold_crossed", BudgetThresholdCrossedV1.VERSION, BudgetThresholdCrossedV1.class);
  /** A liability recorded, re-termed or closed. */
  public static final EventType<LiabilityChangedV1> LIABILITY_CHANGED =
      EventType.of("planning", "liability", "changed", LiabilityChangedV1.VERSION, LiabilityChangedV1.class);

  /** The aggregates planning publishes (topics dexter.planning.&lt;aggregate&gt;.v1, created by the relay). */
  public static final List<String> AGGREGATES = List.of("budget", "liability");

  private PlanningEvents() {}
}
