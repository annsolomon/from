package in.dexter.reco.domain;

import in.dexter.portfolio.calc.RewardTerms;
import in.dexter.reco.domain.CardTerms.Rule;
import java.util.List;

/**
 * Which rule earns on a given category root, and what a rule returns on a month's spend (MASTER_PLAN P14).
 *
 * <p>The spend profile is per <em>root</em> while rules can be written on any depth up to four labels, so a rule
 * on {@code food.dining} is being asked about the whole {@code food} root. This class makes that approximation in
 * one place: {@link #share} says what fraction of a root's spend a rule is assumed to see, and nothing else in the
 * engine guesses. The assumption is deliberately conservative — a deeper rule is credited with less of the root —
 * and {@code Savings} reports it in the breakdown so a reader can see where a figure came from.
 */
public final class Rules {
  /** What fraction (in bps) of a root's spend a rule at each depth is assumed to cover. */
  static final int[] SHARE_BPS_BY_DEPTH = {10_000, 10_000, 4_000, 2_000, 1_000};

  private Rules() {}

  /**
   * The share of {@code root}'s monthly spend this rule is assumed to earn on, in basis points, or 0 when the rule
   * cannot earn on the root at all.
   *
   * <p>Depth 0 (no category) and depth 1 (the root itself) see all of it. A rule on {@code food.dining} sees 40 %
   * of {@code food}, one on {@code food.dining.coffee} 20 %, and a fourth-level rule 10 %. These are assumptions,
   * not measurements: P15's optimizer, which has the per-category detail, supersedes them (A-81).
   */
  public static int share(Rule rule, String root) {
    String ruleRoot = rule.root();
    if (ruleRoot != null && !ruleRoot.equals(root)) {
      return 0;
    }
    for (String excluded : rule.excludedPaths()) {
      if (excluded == null || excluded.isBlank()) {
        continue;
      }
      int dot = excluded.indexOf('.');
      String excludedRoot = dot < 0 ? excluded : excluded.substring(0, dot);
      // an exclusion of the whole root removes the rule; a deeper exclusion only trims it, which the share below
      // already under-counts, so it is not subtracted twice
      if (excludedRoot.equals(root) && dot < 0) {
        return 0;
      }
    }
    int depth = Math.min(rule.depth(), SHARE_BPS_BY_DEPTH.length - 1);
    return SHARE_BPS_BY_DEPTH[depth];
  }

  /**
   * The best rule for a root: the deepest match wins, then the higher {@code priority}, then the higher rate, then
   * the rule id so the choice is deterministic. Returns null when no rule earns on the root.
   */
  public static Rule best(List<Rule> rules, String root) {
    Rule best = null;
    for (Rule r : rules) {
      if (share(r, root) == 0) {
        continue;
      }
      if (best == null || beats(r, best)) {
        best = r;
      }
    }
    return best;
  }

  private static boolean beats(Rule a, Rule b) {
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
   * What {@code spendPaise} of a month's spend earns under this rule, before any cap, in paise.
   *
   * <p>The arithmetic is {@link RewardTerms#earn}'s, not reco's: {@code rateBps} is a value rate for cashback and
   * points alike, and a points card's value is rounded down to whole points. Reco computing this itself would be a
   * second answer to a question portfolio already answers for the same card (A-82), and the two would drift.
   * A rule whose {@code minTxnPaise} exceeds the spend earns nothing, which {@code earn} also decides.
   */
  public static long rewardPaise(Rule rule, long spendPaise) {
    if (spendPaise <= 0 || rule.rateBps() == 0) {
      return 0;
    }
    // the path the rule itself is written on, so RewardTerms' ancestor-or-self matching selects this rule; a rule
    // with no category is asked about a path it covers by definition
    String path = rule.categoryPath() == null || rule.categoryPath().isBlank() ? "any" : rule.categoryPath();
    RewardTerms terms = new RewardTerms(List.of(rule.shared()));
    return terms.earn(spendPaise, path, null).valuePaise();
  }
}
