package in.dexter.reco.app;

import in.dexter.contracts.events.reco.RecommendationUpdatedV1;
import in.dexter.events.EventType;
import java.util.List;

/** Reco's event types (contracts/events/reco.*.v1.json). */
public final class RecoEvents {
  /**
   * A recompute changed an account's result. Published only when the state hash moved, so an unchanged recompute is
   * silent (MASTER_PLAN P14's debounce is about the recompute; this is about the notification).
   */
  public static final EventType<RecommendationUpdatedV1> RECOMMENDATION_UPDATED = EventType.of("reco",
      "recommendation", "updated", RecommendationUpdatedV1.VERSION, RecommendationUpdatedV1.class);

  /** The aggregates reco publishes (topics dexter.reco.&lt;aggregate&gt;.v1, created by the relay). */
  public static final List<String> AGGREGATES = List.of("recommendation");

  private RecoEvents() {}
}
