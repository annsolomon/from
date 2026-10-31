package in.dexter.reco.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The 60/40 blend and the ranking (MASTER_PLAN P14). Pure and deterministic: the same inputs give the same order,
 * byte for byte, which is what lets a {@code state_hash} stand in for "nothing to recompute".
 *
 * <p>Both components are normalized to 0-10000 bps before blending. Savings is normalized against the best card in
 * the candidate set rather than an absolute ceiling, because what matters to a reader is "how much better is the
 * best card than this one". A set where every card loses money normalizes to 0 across the board rather than
 * flattering the least bad one.
 */
public final class Scorer {
  /** MASTER_PLAN's blend: 60 % savings, 40 % expense fit. Configurable per the plan. */
  public static final int DEFAULT_SAVINGS_WEIGHT_BPS = 6_000;
  /** Below this blended score a card goes in the "not recommended" bucket rather than the ranking. */
  public static final int DEFAULT_THRESHOLD_BPS = 2_000;

  private Scorer() {}

  /** One scored card: the components, the blend, and the explanation behind the savings. */
  public record Scored(String cardProductId, String slug, String name, String issuerCode, boolean held,
      long annualSavingsPaise, int savingsBps, int fitBps, int scoreBps, Savings.Result savings,
      Map<String, Integer> rewardVector, List<String> reasons) {}

  /** A ranking plus the cards that did not clear the threshold. */
  public record Ranking(List<Scored> recommended, List<Scored> notRecommended, int savingsWeightBps,
      int thresholdBps) {
    public Ranking {
      recommended = recommended == null ? List.of() : List.copyOf(recommended);
      notRecommended = notRecommended == null ? List.of() : List.copyOf(notRecommended);
    }
  }

  public static Ranking rank(List<CardTerms> candidates, SpendVector spend, java.util.Set<String> heldProductIds) {
    return rank(candidates, spend, heldProductIds, DEFAULT_SAVINGS_WEIGHT_BPS, DEFAULT_THRESHOLD_BPS);
  }

  public static Ranking rank(List<CardTerms> candidates, SpendVector spend, java.util.Set<String> heldProductIds,
      int savingsWeightBps, int thresholdBps) {
    if (savingsWeightBps < 0 || savingsWeightBps > 10_000) {
      throw new IllegalArgumentException("the savings weight is 0 to 10000 bps");
    }
    List<Savings.Result> all = new ArrayList<>(candidates.size());
    long bestSavings = 0;
    for (CardTerms c : candidates) {
      Savings.Result r = Savings.of(c, spend);
      all.add(r);
      bestSavings = Math.max(bestSavings, r.annualSavingsPaise());
    }

    List<Scored> scored = new ArrayList<>(candidates.size());
    for (int i = 0; i < candidates.size(); i++) {
      CardTerms c = candidates.get(i);
      Savings.Result r = all.get(i);
      int savingsBps = bestSavings <= 0 ? 0
          : (int) Math.max(0, Math.min(10_000, r.annualSavingsPaise() * 10_000L / bestSavings));
      int fitBps = ExpenseFit.bps(c, spend);
      int scoreBps = (savingsBps * savingsWeightBps + fitBps * (10_000 - savingsWeightBps)) / 10_000;
      scored.add(new Scored(c.cardProductId(), c.slug(), c.name(), c.issuerCode(),
          heldProductIds != null && heldProductIds.contains(c.cardProductId()), r.annualSavingsPaise(), savingsBps,
          fitBps, scoreBps, r, ExpenseFit.rewardVector(c, spend), Reasons.of(c, r, spend, fitBps)));
    }

    // highest score, then the bigger rupee saving, then the slug: a stable order for a stable state hash
    scored.sort(Comparator.comparingInt(Scored::scoreBps).reversed()
        .thenComparing(Comparator.comparingLong(Scored::annualSavingsPaise).reversed())
        .thenComparing(s -> String.valueOf(s.slug())));

    List<Scored> yes = new ArrayList<>();
    List<Scored> no = new ArrayList<>();
    for (Scored s : scored) {
      // a card that loses money is never "recommended", whatever its fit
      if (s.scoreBps() >= thresholdBps && s.annualSavingsPaise() > 0) {
        yes.add(s);
      } else {
        no.add(s);
      }
    }
    return new Ranking(yes, no, savingsWeightBps, thresholdBps);
  }
}
