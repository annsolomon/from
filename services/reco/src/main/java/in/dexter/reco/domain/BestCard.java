package in.dexter.reco.domain;

import in.dexter.reco.domain.CardTerms.Milestone;
import in.dexter.reco.domain.CardTerms.Rule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Which card the account should put <em>this</em> purchase on (MASTER_PLAN P15, best-card-now). Pure and
 * deterministic; the caller supplies the account's held cards, what each card's rules have already earned this
 * period, and the offers projection.
 *
 * <p>This is a different question from {@code GET /v1/recommendations}, which ranks cards the account could get
 * against a year of average spend. Here the account is standing at a till with one amount, one merchant and one
 * category, and only the cards it already holds are on the table. So the arithmetic is per transaction, and three
 * things that an annual average hides decide it:
 *
 * <ol>
 *   <li><b>Cap headroom.</b> A 5 % card whose monthly cap is already spent earns nothing more this month, so a
 *       2 % card with headroom beats it. This is the test the plan names, and it is why reco keeps per-card spend
 *       (A-86) rather than only the per-category profile P14 built.</li>
 *   <li><b>Offers.</b> A merchant or category offer on this card can be worth more than the reward rate, and it
 *       applies to this purchase only.</li>
 *   <li><b>Milestone proximity.</b> A purchase that takes a card over its spend milestone is worth the milestone
 *       as well, and only the card that is close enough for this purchase to tip it gets that credit.</li>
 * </ol>
 */
public final class BestCard {
  private BestCard() {}

  /** One held card, with what reco knows about its current period. */
  public record Held(String userCardId, String cardProductId, CardTerms terms, long periodSpendPaise,
      Map<String, Long> rewardEarnedByRule) {
    public Held {
      rewardEarnedByRule = rewardEarnedByRule == null ? Map.of() : Map.copyOf(rewardEarnedByRule);
    }

    /** What this rule has already earned in the current period. */
    public long earned(String ruleId) {
      Long v = rewardEarnedByRule.get(ruleId);
      return v == null ? 0 : v;
    }
  }

  /**
   * One card's answer for this purchase.
   *
   * @param headroomPaise what is left of the matched rule's cap this period, or null when the rule is uncapped
   * @param capExhausted true when the rule has a cap and nothing is left of it
   */
  public record Pick(String userCardId, String cardProductId, String slug, String name, String ruleId, int rateBps,
      long rewardPaise, long offerPaise, long milestonePaise, long totalPaise, Long headroomPaise,
      boolean capExhausted, List<Offers.Offer> offers, List<String> reasons) {
    public Pick {
      offers = offers == null ? List.of() : List.copyOf(offers);
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
  }

  /** The ranked answer: every held card, best first. */
  public record Answer(long amountPaise, String merchantId, String categoryPath, List<Pick> picks) {
    public Answer {
      picks = picks == null ? List.of() : List.copyOf(picks);
    }

    public Pick best() {
      return picks.isEmpty() ? null : picks.get(0);
    }
  }

  /**
   * Ranks the held cards for one purchase.
   *
   * @param categoryPath the purchase's full ltree path, not its root: at a till the category is known exactly, so
   *     best-card-now does not need {@link Rules#share}'s root approximation (A-81) at all
   */
  public static Answer rank(List<Held> held, List<Offers.Offer> offers, String merchantId, String categoryPath,
      long amountPaise, Instant when) {
    if (amountPaise <= 0) {
      throw new IllegalArgumentException("an amount is positive");
    }
    List<Pick> picks = new ArrayList<>(held.size());
    for (Held h : held) {
      picks.add(pick(h, offers, merchantId, categoryPath, amountPaise, when));
    }
    // most value first, then the bigger reward, then the card id so the order is stable
    picks.sort(Comparator.comparingLong(Pick::totalPaise).reversed()
        .thenComparing(Comparator.comparingLong(Pick::rewardPaise).reversed())
        .thenComparing(p -> String.valueOf(p.userCardId())));
    return new Answer(amountPaise, merchantId, categoryPath, picks);
  }

  private static Pick pick(Held h, List<Offers.Offer> offers, String merchantId, String categoryPath,
      long amountPaise, Instant when) {
    CardTerms t = h.terms();
    List<String> reasons = new ArrayList<>();

    Rule rule = bestRuleFor(t.rules(), merchantId, categoryPath);
    long reward = 0;
    Long headroom = null;
    boolean exhausted = false;
    String ruleId = null;
    int rateBps = 0;
    if (rule != null) {
      ruleId = rule.ruleId();
      rateBps = rule.rateBps();
      // the full path is known here, so the rule earns on the whole amount - no root share to assume
      reward = Rules.rewardPaise(rule, amountPaise);
      Long cap = rule.monthlyCapPaise();
      if (cap != null) {
        long left = Math.max(0, cap - h.earned(rule.ruleId()));
        headroom = left;
        if (reward > left) {
          reward = left;
          exhausted = left == 0;
          reasons.add(exhausted ? "this month's cap on this card is already used"
              : "only " + left + " paise of this month's cap is left");
        }
      }
      if (reward > 0) {
        reasons.add(rateBps + " bps on " + (rule.categoryPath() == null ? "everything" : rule.categoryPath()));
      }
    }

    Offers.Applied applied = Offers.bestFor(offers, h.cardProductId(), merchantId, categoryPath, amountPaise, when);
    if (applied.totalPaise() > 0) {
      reasons.add(applied.offers().size() == 1 ? "offer: " + applied.offers().get(0).title()
          : applied.offers().size() + " offers apply");
    }

    long milestone = milestoneCredit(t.milestone(), h.periodSpendPaise(), amountPaise);
    if (milestone > 0) {
      reasons.add("this purchase reaches the spend milestone");
    }
    if (reasons.isEmpty()) {
      reasons.add("no reward rule on this card covers this purchase");
    }

    long total = reward + applied.totalPaise() + milestone;
    return new Pick(h.userCardId(), h.cardProductId(), t.slug(), t.name(), ruleId, rateBps, reward,
        applied.totalPaise(), milestone, total, headroom, exhausted, applied.offers(), reasons);
  }

  /**
   * The rule that earns on this purchase: a merchant rule beats a category rule, then the deepest category match,
   * then priority, then the rate. Unlike {@link Rules#best}, the category is the purchase's exact path, so a rule
   * on {@code food.dining} matches a {@code food.dining.coffee} purchase and a rule on {@code food.dining.coffee}
   * does not match a {@code food.dining} one.
   */
  static Rule bestRuleFor(List<Rule> rules, String merchantId, String categoryPath) {
    Rule best = null;
    for (Rule r : rules) {
      if (!matches(r, merchantId, categoryPath)) {
        continue;
      }
      if (best == null || beats(r, best)) {
        best = r;
      }
    }
    return best;
  }

  private static boolean matches(Rule r, String merchantId, String categoryPath) {
    if (r.merchantId() != null && !r.merchantId().isBlank() && !r.merchantId().equals(merchantId)) {
      return false;
    }
    for (String excluded : r.excludedPaths()) {
      if (excluded != null && !excluded.isBlank() && Offers.covers(excluded, categoryPath)) {
        return false;
      }
    }
    return r.categoryPath() == null || r.categoryPath().isBlank()
        || Offers.covers(r.categoryPath(), categoryPath);
  }

  private static boolean beats(Rule a, Rule b) {
    boolean am = a.merchantId() != null && !a.merchantId().isBlank();
    boolean bm = b.merchantId() != null && !b.merchantId().isBlank();
    if (am != bm) {
      return am;                                  // a rule written for this merchant wins over a category rule
    }
    if (a.depth() != b.depth()) {
      return a.depth() > b.depth();
    }
    if (a.priority() != b.priority()) {
      return a.priority() > b.priority();
    }
    if (a.rateBps() != b.rateBps()) {
      return a.rateBps() > b.rateBps();
    }
    return String.valueOf(a.ruleId()).compareTo(String.valueOf(b.ruleId())) < 0;
  }

  /**
   * The milestone this purchase would unlock, or 0. Only a purchase that actually crosses the threshold is
   * credited: crediting proximity would make every card with a far-off milestone look better than it is.
   */
  public static long milestoneCredit(Milestone m, long periodSpendPaise, long amountPaise) {
    if (m == null) {
      return 0;
    }
    long threshold = m.monthlyThresholdPaise();
    if (periodSpendPaise >= threshold) {
      return 0;                                   // already reached, so this purchase is not what earns it
    }
    return periodSpendPaise + amountPaise >= threshold ? m.monthlyBenefitPaise() : 0;
  }
}
