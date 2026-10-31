package in.dexter.reco.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The savings arithmetic (MASTER_PLAN P14): cap binding, the fee waiver ramp and milestones. */
class SavingsTest {

  static CardTerms.Rule rule(String id, String path, String type, int rateBps, long pointValue, Long cap,
      String capPeriod) {
    return new CardTerms.Rule(id, path, null, type, rateBps, pointValue, cap, capPeriod, 0, List.of(), 0);
  }

  static SpendVector spend() {
    Map<String, Long> m = new LinkedHashMap<>();
    m.put("food", 2_000_000L);      // Rs 20,000 a month
    m.put("travel", 500_000L);      // Rs 5,000 a month
    return SpendVector.of(m);
  }

  static Savings.Line lineOf(Savings.Result r, String root) {
    return r.lines().stream().filter(l -> l.categoryRoot().equals(root)).findFirst().orElseThrow();
  }

  @Test
  void capBindingIsReportedNotHidden() {
    // 5 % on food would earn Rs 1,000 a month, but the cap is Rs 500
    CardTerms card = new CardTerms("p1", "slug-1", "Card One", "HDFC", 100_000L, 30_000_000L,
        List.of(rule("food5", "food", "cashback", 500, 0, 50_000L, "month"),
            rule("flat1", null, "cashback", 100, 0, null, null)), null);
    Savings.Result r = Savings.of(card, spend());

    assertThat(lineOf(r, "food").annualRewardPaise()).as("Rs 500 a month, not Rs 1,000").isEqualTo(600_000L);
    assertThat(lineOf(r, "food").capBound()).isTrue();
    assertThat(lineOf(r, "food").annualCapPaise()).isEqualTo(600_000L);
    assertThat(lineOf(r, "travel").annualRewardPaise()).as("1 % of Rs 5,000 x 12").isEqualTo(60_000L);
    assertThat(lineOf(r, "travel").capBound()).isFalse();
    assertThat(r.annualRewardPaise()).isEqualTo(660_000L);
  }

  @Test
  void aCapPerTransactionCannotBeConvertedAndSaysSo() {
    CardTerms card = new CardTerms("p1", "s", "n", "HDFC", 0, null,
        List.of(rule("food5", "food", "cashback", 500, 0, 5_000L, "txn")), null);
    Savings.Line line = lineOf(Savings.of(card, spend()), "food");
    assertThat(line.capUnconvertible()).as("a per-txn cap needs a transaction count reco does not have").isTrue();
    assertThat(line.annualCapPaise()).isNull();
    assertThat(line.capBound()).isFalse();
  }

  @Test
  void quarterlyAndYearlyCapsBecomeMonthlyCeilings() {
    assertThat(rule("c", "food", "cashback", 500, 0, 30_000L, "quarter").monthlyCapPaise()).isEqualTo(10_000L);
    assertThat(rule("c", "food", "cashback", 500, 0, 120_000L, "year").monthlyCapPaise()).isEqualTo(10_000L);
    assertThat(rule("c", "food", "cashback", 500, 0, 50_000L, "month").monthlyCapPaise()).isEqualTo(50_000L);
    assertThat(rule("c", "food", "cashback", 500, 0, 50_000L, "statement").monthlyCapPaise()).isEqualTo(50_000L);
  }

  @Test
  void theFeeWaiverIsARampNotAStep() {
    // a step would make the recommendation flip on one rupee of spend
    assertThat(Savings.waiverProbabilityBps(null, 100_000_000L)).as("no threshold, no waiver").isZero();
    assertThat(Savings.waiverProbabilityBps(30_000_000L, 40_000_000L)).as("over the threshold").isEqualTo(10_000);
    assertThat(Savings.waiverProbabilityBps(30_000_000L, 30_000_000L)).as("exactly at it").isEqualTo(10_000);
    assertThat(Savings.waiverProbabilityBps(30_000_000L, 15_000_000L)).as("at half, nothing").isZero();
    assertThat(Savings.waiverProbabilityBps(40_000_000L, 30_000_000L)).as("three quarters of the way")
        .isEqualTo(5_000);
    assertThat(Savings.waiverProbabilityBps(30_000_000L, 0L)).isZero();
  }

  @Test
  void theFeeIsCountedAtItsExpectedValue() {
    CardTerms card = new CardTerms("p1", "s", "n", "HDFC", 100_000L, 30_000_000L,
        List.of(rule("flat", null, "cashback", 100, 0, null, null)), null);
    // Rs 25,000 a month = Rs 3,00,000 a year: the waiver is reached, so the fee is not counted
    Savings.Result big = Savings.of(card, spend());
    assertThat(big.fee().waiverProbabilityBps()).isEqualTo(10_000);
    assertThat(big.fee().expectedFeePaise()).isZero();
    assertThat(big.annualSavingsPaise()).isEqualTo(big.annualRewardPaise());

    // a tenth of that spend: the full fee, and the savings are net of it
    Savings.Result small = Savings.of(card, SpendVector.of(Map.of("food", 200_000L)));
    assertThat(small.fee().expectedFeePaise()).isEqualTo(100_000L);
    assertThat(small.annualRewardPaise()).isEqualTo(24_000L);        // 1 % of Rs 2,000 x 12
    assertThat(small.annualSavingsPaise()).isEqualTo(24_000L - 100_000L);
  }

  @Test
  void aFeeHeavyCardHasNegativeSavings() {
    CardTerms pricey = new CardTerms("p2", "s", "n", "AXIS", 1_000_000L, null,
        List.of(rule("flat", null, "cashback", 100, 0, null, null)), null);
    assertThat(Savings.of(pricey, SpendVector.of(Map.of("food", 200_000L))).annualSavingsPaise()).isNegative();
  }

  @Test
  void onlyAMilestoneTheSpendReachesIsCounted() {
    CardTerms card = new CardTerms("p3", "s", "n", "SBI", 0, null,
        List.of(rule("flat", null, "cashback", 100, 0, null, null)),
        new CardTerms.Milestone(12_000_000L, "year", "voucher", 600_000L));   // Rs 1,00,000 a year
    Savings.MilestoneValue reached = Savings.of(card, spend()).milestone();
    assertThat(reached.reached()).isTrue();
    assertThat(reached.annualValuePaise()).isEqualTo(600_000L);
    assertThat(reached.monthlyShortfallPaise()).isZero();

    Savings.MilestoneValue missed = Savings.of(card, SpendVector.of(Map.of("food", 10_000L))).milestone();
    assertThat(missed.reached()).isFalse();
    assertThat(missed.annualValuePaise()).as("crediting an unreachable benefit would flatter the card").isZero();
    assertThat(missed.monthlyShortfallPaise()).isEqualTo(1_000_000L - 10_000L);
  }

  @Test
  void aMinimumTransactionAboveAMonthsSpendEarnsNothing() {
    CardTerms card = new CardTerms("p4", "s", "n", "ICICI", 0, null,
        List.of(new CardTerms.Rule("m", "food", null, "cashback", 500, 0, null, null, 5_000_000L, List.of(), 0)),
        null);
    assertThat(Savings.of(card, spend()).annualRewardPaise()).isZero();
    assertThat(Savings.of(card, spend()).lines()).isEmpty();
  }

  @Test
  void anEmptyProfileScoresWithoutThrowing() {
    CardTerms card = new CardTerms("p1", "s", "n", "HDFC", 100_000L, null,
        List.of(rule("flat", null, "cashback", 100, 0, null, null)), null);
    Savings.Result r = Savings.of(card, SpendVector.empty());
    assertThat(r.annualRewardPaise()).isZero();
    assertThat(r.fee().expectedFeePaise()).isEqualTo(100_000L);
    assertThat(r.annualSavingsPaise()).isEqualTo(-100_000L);
  }
}
