package in.dexter.portfolio.domain;

import java.util.List;
import java.util.UUID;

/**
 * What a card earns (MASTER_PLAN P11, from the catalog projection's snapshot): its reward rules. The rule that
 * applies to a purchase is the merchant rule for the purchase's merchant if there is one, else the most specific
 * category rule covering the purchase's category (ancestor-or-self, or the base rule) that does not exclude it;
 * higher priority breaks ties (catalog's semantics, ADR-0014 §5). Earned value = amount × rate; points/miles =
 * value ÷ the point value. Caps and minimum spends are left to the calc engine (P12). Pure.
 */
public record RewardTerms(List<Rule> rules) {

  public record Rule(String categoryPath, UUID merchantId, String rewardType, int rateBps, long pointValueMilliPaise,
      long minTxnPaise, List<String> excludedPaths, int priority) {}

  /** What one purchase earns: value in milli-paise; points 0 for cashback. */
  public record Earning(String rewardType, int rateBps, long valueMilliPaise, long points, long pointValueMilliPaise) {
    public static final Earning NONE = new Earning("none", 0, 0, 0, 0);

    public long valuePaise() {
      return valueMilliPaise / 1000;
    }
  }

  public RewardTerms {
    rules = rules == null ? List.of() : List.copyOf(rules);
  }

  public Rule ruleFor(String categoryPath, UUID merchantId) {
    Rule best = null;
    if (merchantId != null) {
      for (Rule r : rules) {
        if (merchantId.equals(r.merchantId()) && (best == null || r.priority() > best.priority())) {
          best = r;
        }
      }
      if (best != null) {
        return best;
      }
    }
    int bestLevel = -1;
    for (Rule r : rules) {
      if (r.merchantId() != null || !covers(r.categoryPath(), categoryPath) || excludes(r, categoryPath)) {
        continue;
      }
      int level = r.categoryPath() == null ? 0 : r.categoryPath().split("\\.").length;
      if (level > bestLevel || level == bestLevel && r.priority() > best.priority()) {
        best = r;
        bestLevel = level;
      }
    }
    return best;
  }

  public Earning earn(long amountPaise, String categoryPath, UUID merchantId) {
    Rule r = ruleFor(categoryPath, merchantId);
    if (r == null || r.rateBps() <= 0 || amountPaise < r.minTxnPaise()) {
      return Earning.NONE;
    }
    long valueMilliPaise = Math.multiplyExact(amountPaise, (long) r.rateBps()) * 1000 / 10_000;
    boolean points = !"cashback".equals(r.rewardType()) && r.pointValueMilliPaise() > 0;
    long pts = points ? valueMilliPaise / r.pointValueMilliPaise() : 0;
    long value = points ? pts * r.pointValueMilliPaise() : valueMilliPaise;
    return new Earning(r.rewardType(), r.rateBps(), value, pts, points ? r.pointValueMilliPaise() : 0);
  }

  static boolean covers(String rulePath, String path) {
    if (rulePath == null) {
      return true;
    }
    return path != null && (path.equals(rulePath) || path.startsWith(rulePath + "."));
  }

  static boolean excludes(Rule r, String path) {
    if (path == null || r.excludedPaths() == null) {
      return false;
    }
    for (String e : r.excludedPaths()) {
      if (covers(e, path)) {
        return true;
      }
    }
    return false;
  }
}
