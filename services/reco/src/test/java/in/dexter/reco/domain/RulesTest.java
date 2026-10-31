package in.dexter.reco.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rule matching and the earning arithmetic. The earning numbers are portfolio's {@code RewardTerms}, not reco's
 * (A-82): these tests pin the agreement, so the two services cannot drift on what a card is worth.
 */
class RulesTest {

  static CardTerms.Rule rule(String id, String path, String type, int rateBps, long pointValue) {
    return new CardTerms.Rule(id, path, null, type, rateBps, pointValue, null, null, 0, List.of(), 0);
  }

  @Test
  void cashbackIsTheRateTimesTheSpend() {
    assertThat(Rules.rewardPaise(rule("r", "food", "cashback", 500, 0), 1_000_000L))
        .as("5 % of Rs 10,000").isEqualTo(50_000L);
    assertThat(Rules.rewardPaise(rule("r", "food", "cashback", 500, 0), 0)).isZero();
    assertThat(Rules.rewardPaise(rule("r", "food", "cashback", 0, 0), 1_000_000L)).isZero();
  }

  @Test
  void rateBpsIsAValueRateForPointsToo() {
    // portfolio's meaning since P11 (ADR-0014 §5): 4 % of Rs 10,000 = Rs 400, bought as whole Rs 0.25 points
    assertThat(Rules.rewardPaise(rule("r", "travel", "points", 400, 250), 1_000_000L)).isEqualTo(40_000L);
    // a coarse point value still lands on the same value when it divides evenly
    assertThat(Rules.rewardPaise(rule("r", "travel", "points", 400, 10_000), 1_000_000L)).isEqualTo(40_000L);
    // 1.33 % of Rs 750 is Rs 9.975, which buys 39 points of Rs 0.25 = Rs 9.75 - the remainder is lost, as on a
    // real card
    assertThat(Rules.rewardPaise(rule("r", null, "points", 133, 25_000), 75_000L)).isEqualTo(975L);
  }

  @Test
  void aPointsRuleWithoutAPointValueEarnsTheValueRate() {
    assertThat(Rules.rewardPaise(rule("r", null, "points", 400, 0), 1_000_000L)).isEqualTo(40_000L);
  }

  @Test
  void aRulesShareOfARootFallsWithItsDepth() {
    assertThat(Rules.share(rule("a", null, "cashback", 100, 0), "food")).as("no category: all of it")
        .isEqualTo(10_000);
    assertThat(Rules.share(rule("a", "food", "cashback", 100, 0), "food")).as("the root itself").isEqualTo(10_000);
    assertThat(Rules.share(rule("a", "food.dining", "cashback", 100, 0), "food")).isEqualTo(4_000);
    assertThat(Rules.share(rule("a", "food.dining.coffee", "cashback", 100, 0), "food")).isEqualTo(2_000);
    assertThat(Rules.share(rule("a", "travel", "cashback", 100, 0), "food")).as("another root").isZero();
  }

  @Test
  void anExclusionOfTheWholeRootRemovesTheRule() {
    CardTerms.Rule excluded = new CardTerms.Rule("x", null, null, "cashback", 500, 0, null, null, 0,
        List.of("food"), 0);
    assertThat(Rules.share(excluded, "food")).isZero();
    assertThat(Rules.share(excluded, "travel")).as("other roots are untouched").isEqualTo(10_000);

    CardTerms.Rule deeper = new CardTerms.Rule("x", null, null, "cashback", 500, 0, null, null, 0,
        List.of("food.dining"), 0);
    assertThat(Rules.share(deeper, "food")).as("a deeper exclusion only trims, which the share already under-counts")
        .isEqualTo(10_000);
  }

  @Test
  void theDeepestRuleWinsThenPriorityThenRate() {
    List<CardTerms.Rule> rules = List.of(
        rule("flat", null, "cashback", 100, 0),
        rule("food", "food", "cashback", 300, 0),
        rule("dining", "food.dining", "cashback", 1000, 0));
    assertThat(Rules.best(rules, "food").ruleId()).isEqualTo("dining");
    assertThat(Rules.best(rules, "travel").ruleId()).as("the flat rule covers every other root").isEqualTo("flat");

    List<CardTerms.Rule> sameDepth = List.of(
        new CardTerms.Rule("low", "food", null, "cashback", 900, 0, null, null, 0, List.of(), 1),
        new CardTerms.Rule("high", "food", null, "cashback", 100, 0, null, null, 0, List.of(), 9));
    assertThat(Rules.best(sameDepth, "food").ruleId()).as("priority beats rate at the same depth")
        .isEqualTo("high");
  }

  @Test
  void noRuleForARootIsNull() {
    assertThat(Rules.best(List.of(rule("travel", "travel", "cashback", 500, 0)), "food")).isNull();
    assertThat(Rules.best(List.of(), "food")).isNull();
  }
}
