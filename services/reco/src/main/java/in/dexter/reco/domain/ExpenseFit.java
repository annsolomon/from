package in.dexter.reco.domain;

import in.dexter.reco.domain.CardTerms.Rule;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How well a card's reward shape overlaps what the account actually spends on (MASTER_PLAN P14: "a cosine-like
 * overlap of the reward vector with the spend vector").
 *
 * <p>Savings already answers "how much"; this answers "is this card aimed at me". A card earning 5 % on travel
 * scores badly for someone who never travels even if its headline rate is high, and that is the signal the 60/40
 * blend keeps separate from the rupees.
 *
 * <p>The result is basis points of 1, so 10000 = a perfect overlap. Integer arithmetic throughout - no floating
 * point, no locale, so the same inputs give the same bps on every machine, which is what the state hash needs.
 */
public final class ExpenseFit {
  private ExpenseFit() {}

  /** The card's reward vector: the best rate (bps, as a value share) per root the account spends on. */
  public static Map<String, Integer> rewardVector(CardTerms card, SpendVector spend) {
    Map<String, Integer> v = new LinkedHashMap<>();
    for (String root : spend.descending().keySet()) {
      Rule rule = Rules.best(card.rules(), root);
      if (rule == null) {
        v.put(root, 0);
        continue;
      }
      // the value rate: what a rupee of spend in this root returns, in bps, after the share assumption
      long monthly = Math.max(1, spend.monthly(root));
      long considered = monthly * Rules.share(rule, root) / 10_000L;
      long reward = Rules.rewardPaise(rule, considered);
      v.put(root, (int) Math.min(10_000, reward * 10_000L / monthly));
    }
    return v;
  }

  /**
   * The overlap of the reward vector with the spend vector, in bps of 1.
   *
   * <p>{@code dot / (|spend| × |reward|)} would be the cosine of the angle between them, which is 10000 whenever
   * the two point the same way regardless of magnitude — so a card with a flat 0.1 % everywhere would score a
   * perfect fit. Instead this weights each root by its share of spend and by how close its rate is to the card's
   * own best rate, which is what "aimed at me" means: the card's strongest rewards sit where the money goes.
   */
  public static int bps(CardTerms card, SpendVector spend) {
    long total = spend.monthlyTotal();
    if (total == 0) {
      return 0;
    }
    Map<String, Integer> reward = rewardVector(card, spend);
    int bestRate = 0;
    for (int r : reward.values()) {
      bestRate = Math.max(bestRate, r);
    }
    if (bestRate == 0) {
      return 0;
    }
    long weighted = 0;
    for (Map.Entry<String, Long> e : spend.monthlyByRoot().entrySet()) {
      long share = e.getValue();
      if (share <= 0) {
        continue;
      }
      int rate = reward.getOrDefault(e.getKey(), 0);
      weighted += share * rate / bestRate;
    }
    return (int) Math.min(10_000, weighted * 10_000L / total);
  }
}
