package in.dexter.reco.domain;

import in.dexter.portfolio.calc.RewardTerms;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One card product's terms as the scorer needs them (MASTER_PLAN P14), projected from the catalog snapshot. Money
 * is paise, rates are basis points, and a point's worth is milli-paise so a 1 % cashback and a 4-points-per-₹150
 * card are comparable without floating point.
 */
public record CardTerms(String cardProductId, String slug, String name, String issuerCode, long annualFeePaise,
    Long feeWaiverSpendPaise, List<Rule> rules, Milestone milestone) {

  public CardTerms {
    Objects.requireNonNull(cardProductId, "cardProductId");
    rules = rules == null ? List.of() : List.copyOf(rules);
    if (annualFeePaise < 0) {
      throw new IllegalArgumentException("an annual fee is not negative");
    }
    if (feeWaiverSpendPaise != null && feeWaiverSpendPaise <= 0) {
      throw new IllegalArgumentException("a fee waiver threshold is positive");
    }
  }

  /**
   * A reward rule. {@code categoryPath} null earns on everything {@code excludedPaths} does not remove;
   * {@code capPaise} with {@code capPeriod} bounds the <em>reward</em> (not the spend) in that period.
   *
   * @param rateBps the share of spend returned <em>as value</em>, for cashback and points alike — the meaning
   *     portfolio's {@link RewardTerms} has given it since P11 (ADR-0014 §5). On a points card
   *     {@code pointValueMilliPaise} only decides how many whole points that value buys
   * @param pointValueMilliPaise what one point is worth; 0 for cashback
   */
  public record Rule(String ruleId, String categoryPath, String merchantId, String rewardType, int rateBps,
      long pointValueMilliPaise, Long capPaise, String capPeriod, long minTxnPaise, List<String> excludedPaths,
      int priority) {

    public Rule {
      excludedPaths = excludedPaths == null ? List.of() : List.copyOf(excludedPaths);
      rewardType = rewardType == null ? "cashback" : rewardType;
      if (rateBps < 0) {
        throw new IllegalArgumentException("a reward rate is not negative");
      }
      if ((capPaise == null) != (capPeriod == null)) {
        throw new IllegalArgumentException("capPaise and capPeriod come together");
      }
      if (capPaise != null && capPaise <= 0) {
        throw new IllegalArgumentException("a cap is positive");
      }
    }

    public boolean cashback() {
      return "cashback".equals(rewardType);
    }

    /** The same rule in the shared library's shape, so the earning arithmetic has one implementation (A-82). */
    public RewardTerms.Rule shared() {
      UUID merchant = null;
      if (merchantId != null && !merchantId.isBlank()) {
        try {
          merchant = UUID.fromString(merchantId);
        } catch (IllegalArgumentException ignored) {
          merchant = null;   // a malformed id in a projection is treated as "no merchant rule"
        }
      }
      return new RewardTerms.Rule(categoryPath, merchant, rewardType, rateBps, pointValueMilliPaise, minTxnPaise,
          excludedPaths, priority);
    }

    /** The root this rule earns on, or empty when it earns on everything. */
    public String root() {
      if (categoryPath == null || categoryPath.isBlank()) {
        return null;
      }
      int dot = categoryPath.indexOf('.');
      return dot < 0 ? categoryPath : categoryPath.substring(0, dot);
    }

    /** How deep the rule's path is: a deeper rule wins over a shallower one for the same spend. */
    public int depth() {
      return categoryPath == null || categoryPath.isBlank() ? 0 : categoryPath.split("\\.").length;
    }

    /** The cap converted to a monthly reward ceiling, or empty when the rule is uncapped. */
    public Long monthlyCapPaise() {
      if (capPaise == null) {
        return null;
      }
      return switch (capPeriod) {
        // a per-transaction cap cannot be converted without a transaction count; Rules.share leaves it uncapped
        // and Savings records it as an approximation rather than inventing a number
        case "txn" -> null;
        case "statement", "month" -> capPaise;
        case "quarter" -> capPaise / 3;
        case "year" -> capPaise / 12;
        default -> throw new IllegalStateException("unknown cap period " + capPeriod);
      };
    }
  }

  /** A spend milestone and what reaching it is worth. */
  public record Milestone(long spendThresholdPaise, String period, String benefitType, long benefitValuePaise) {
    public Milestone {
      if (spendThresholdPaise <= 0) {
        throw new IllegalArgumentException("a milestone threshold is positive");
      }
      if (benefitValuePaise < 0) {
        throw new IllegalArgumentException("a milestone benefit is not negative");
      }
    }

    /** The threshold as a monthly figure, so it can be compared with monthly spend. */
    public long monthlyThresholdPaise() {
      return switch (period) {
        case "statement", "month" -> spendThresholdPaise;
        case "quarter" -> spendThresholdPaise / 3;
        case "year" -> spendThresholdPaise / 12;
        default -> throw new IllegalStateException("unknown milestone period " + period);
      };
    }

    /** The benefit as a monthly figure. */
    public long monthlyBenefitPaise() {
      return switch (period) {
        case "statement", "month" -> benefitValuePaise;
        case "quarter" -> benefitValuePaise / 3;
        case "year" -> benefitValuePaise / 12;
        default -> throw new IllegalStateException("unknown milestone period " + period);
      };
    }
  }
}
