package in.dexter.reco.domain;

import in.dexter.reco.domain.CardTerms.Milestone;
import in.dexter.reco.domain.CardTerms.Rule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one card would save an account in a year, and why (MASTER_PLAN P14):
 *
 * <pre>
 *   savings = Σ over roots of min(spend × rate, cap) − annual fee after the waiver probability + milestone value
 * </pre>
 *
 * Everything is annual paise. The per-root lines, the caps that bound them, the fee and the milestone all come back
 * in {@link Result} so {@code GET /v1/recommendations/explain/{cardProductId}} can show the arithmetic rather than
 * a number.
 */
public final class Savings {
  private Savings() {}

  /**
   * One root's contribution, with the rule that earned it and whether its cap bound the result.
   *
   * @param annualCapPaise the rule's own annual cap, or null when it is uncapped
   * @param annualCapRemainingPaise what was left of that cap when this root was credited, or null when uncapped.
   *     One rule can earn on several roots — a catch-all rule earns on all of them — and its cap applies once
   *     across them, so this is what makes a line reporting less than the cap readable (A-85)
   */
  public record Line(String categoryRoot, String ruleId, String rewardType, int rateBps, int shareBps,
      long monthlySpendPaise, long annualSpendConsideredPaise, long annualRewardPaise, boolean capBound,
      Long annualCapPaise, Long annualCapRemainingPaise, boolean capUnconvertible) {}

  /** The fee after the waiver, with the probability that produced it. */
  public record Fee(long annualFeePaise, Long waiverSpendPaise, int waiverProbabilityBps, long expectedFeePaise) {}

  /** The milestone's expected annual value, and whether the account's spend reaches it. */
  public record MilestoneValue(long annualValuePaise, boolean reached, long monthlyThresholdPaise,
      long monthlyShortfallPaise) {}

  /**
   * @param annualSavingsPaise rewards − expected fee + milestone, which may be negative for a card whose fee
   *     outweighs what it earns on this account
   */
  public record Result(String cardProductId, long annualRewardPaise, Fee fee, MilestoneValue milestone,
      long annualSavingsPaise, List<Line> lines) {
    public Result {
      lines = lines == null ? List.of() : List.copyOf(lines);
    }
  }

  /**
   * The probability (bps) that the account's spend clears the fee waiver, from how far its annual spend is from the
   * threshold. Deliberately a ramp rather than a step: spending 95 % of the threshold is not the same as spending
   * nothing, and a step would make the recommendation flip on a rupee.
   */
  public static int waiverProbabilityBps(Long waiverSpendPaise, long annualSpendPaise) {
    if (waiverSpendPaise == null || waiverSpendPaise <= 0) {
      return 0;
    }
    if (annualSpendPaise <= 0) {
      return 0;
    }
    // 0 % at half the threshold or below, 100 % at the threshold or above, linear between
    long half = waiverSpendPaise / 2;
    if (annualSpendPaise >= waiverSpendPaise) {
      return 10_000;
    }
    if (annualSpendPaise <= half) {
      return 0;
    }
    long span = waiverSpendPaise - half;
    return span == 0 ? 10_000 : (int) ((annualSpendPaise - half) * 10_000L / span);
  }

  /** Scores one card against one spend vector. */
  public static Result of(CardTerms card, SpendVector spend) {
    List<Line> lines = new ArrayList<>();
    long rewards = 0;
    // One rule's cap is one cap, however many roots that rule earns on: a catch-all rule with a Rs 500 a month
    // ceiling earns Rs 500 a month, not Rs 500 per category. So the remaining headroom is carried across the
    // roots, which are visited in descending spend so the biggest root gets the cap first (A-85).
    Map<String, Long> headroom = new LinkedHashMap<>();
    for (Map.Entry<String, Long> e : spend.descending().entrySet()) {
      String root = e.getKey();
      long monthly = e.getValue();
      if (monthly <= 0) {
        continue;
      }
      Rule rule = Rules.best(card.rules(), root);
      if (rule == null) {
        continue;
      }
      int shareBps = Rules.share(rule, root);
      long monthlyConsidered = monthly * shareBps / 10_000L;
      // Rules.rewardPaise is RewardTerms' arithmetic, which returns 0 when the rule's minimum transaction is more
      // than the spend considered - that rule would rarely trigger, so it earns nothing here
      long monthlyReward = Rules.rewardPaise(rule, monthlyConsidered);
      if (monthlyReward == 0) {
        continue;
      }
      Long monthlyCap = rule.monthlyCapPaise();
      boolean capBound = false;
      Long remainingBefore = null;
      if (monthlyCap != null) {
        long left = headroom.computeIfAbsent(rule.ruleId(), id -> monthlyCap);
        remainingBefore = left;
        if (monthlyReward > left) {
          monthlyReward = left;
          capBound = true;
        }
        headroom.put(rule.ruleId(), left - monthlyReward);
      }
      long annualReward = monthlyReward * 12;
      rewards += annualReward;
      // a root the cap has nothing left for still gets a line, at zero: that is where the cap went
      lines.add(new Line(root, rule.ruleId(), rule.rewardType(), rule.rateBps(), shareBps, monthly,
          monthlyConsidered * 12, annualReward, capBound, monthlyCap == null ? null : monthlyCap * 12,
          remainingBefore == null ? null : remainingBefore * 12,
          rule.capPaise() != null && monthlyCap == null));
    }

    long annualSpend = spend.monthlyTotal() * 12;
    int waiverBps = waiverProbabilityBps(card.feeWaiverSpendPaise(), annualSpend);
    long expectedFee = card.annualFeePaise() - card.annualFeePaise() * waiverBps / 10_000L;
    Fee fee = new Fee(card.annualFeePaise(), card.feeWaiverSpendPaise(), waiverBps, expectedFee);

    MilestoneValue ms = milestone(card.milestone(), spend.monthlyTotal());
    return new Result(card.cardProductId(), rewards, fee, ms, rewards - expectedFee + ms.annualValuePaise(), lines);
  }

  /**
   * What each of the card's rules has earned on <em>one month's actual spend</em>, in paise, keyed by rule id.
   *
   * <p>best-card-now needs this to know how much of a cap is left (A-86), and it has to be the same arithmetic the
   * rest of the engine uses — including the shared cap of A-85 — or the headroom it reports would not match the
   * reward it reports. So it reads {@link #of}'s own lines rather than walking the rules again: a line's annual
   * figure is exactly twelve times its monthly one, so the division is exact.
   *
   * @param monthSpend this month's spend by root so far, not a monthly average
   */
  public static Map<String, Long> monthlyEarnedByRule(CardTerms card, SpendVector monthSpend) {
    Map<String, Long> out = new LinkedHashMap<>();
    for (Line l : of(card, monthSpend).lines()) {
      out.merge(l.ruleId(), l.annualRewardPaise() / 12, Long::sum);
    }
    return out;
  }

  private static MilestoneValue milestone(Milestone m, long monthlySpend) {
    if (m == null) {
      return new MilestoneValue(0, false, 0, 0);
    }
    long threshold = m.monthlyThresholdPaise();
    boolean reached = monthlySpend >= threshold;
    // only a milestone the account's spend actually reaches is counted: crediting an unreachable benefit would
    // make an expensive card look good on spend that will never earn it
    long annual = reached ? m.monthlyBenefitPaise() * 12 : 0;
    return new MilestoneValue(annual, reached, threshold, reached ? 0 : threshold - monthlySpend);
  }
}
