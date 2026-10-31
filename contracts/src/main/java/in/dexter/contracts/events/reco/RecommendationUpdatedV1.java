package in.dexter.contracts.events.reco;

import java.util.UUID;

/**
 * {@code reco.recommendation.updated} v1 (MASTER_PLAN P14) on {@code dexter.reco.recommendation.v1} (key = account
 * id): a recompute produced a <em>different</em> result for this account. An unchanged recompute publishes nothing,
 * which is what keeps a burst of expense events from producing a burst of notifications.
 *
 * <p>The payload carries the headline rather than the whole result: a consumer that wants the detail reads
 * {@code GET /v1/recommendations}. {@code stateHash} is the fingerprint of the inputs it was computed from, so a
 * consumer can tell two events apart without comparing payloads.
 */
public record RecommendationUpdatedV1(UUID accountId, String stateHash, String topCardProductId, String topSlug,
    long topAnnualSavingsPaise, int topScoreBps, int recommendedCount, int notRecommendedCount, String computedAt) {
  public static final String TYPE = "reco.recommendation.updated";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.reco.recommendation.v1";
}
