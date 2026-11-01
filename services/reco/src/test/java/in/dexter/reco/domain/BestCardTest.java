package in.dexter.reco.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.reco.domain.CardTerms.Milestone;
import in.dexter.reco.domain.CardTerms.Rule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * best-card-now (MASTER_PLAN P15), including the plan's named test: an exhausted cap must change the answer.
 */
class BestCardTest {
  static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  static Rule rule(String id, String path, String merchant, int rateBps, Long cap) {
    return new Rule(id, path, merchant, "cashback", rateBps, 0, cap, cap == null ? null : "month", 0, List.of(), 0);
  }

  static CardTerms card(String id, List<Rule> rules, Milestone ms) {
    return new CardTerms(id, "slug-" + id, "Card " + id, "ISS", 0, null, rules, ms);
  }

  static BestCard.Held held(String userCardId, CardTerms t, long periodSpend, Map<String, Long> earned) {
    return new BestCard.Held(userCardId, t.cardProductId(), t, periodSpend, earned);
  }

  // ------------------------------------------------------------------------------- the plan's named test

  @Test
  void anExhaustedCapChangesTheAnswer() {
    CardTerms rich = card("rich", List.of(rule("r5", "food", null, 500, 50_000L)), null);
    CardTerms plain = card("plain", List.of(rule("r2", "food", null, 200, null)), null);
    long amount = 500_000L;                                      // Rs 5,000 at a restaurant

    BestCard.Answer fresh = BestCard.rank(
        List.of(held("uc1", rich, 0, Map.of()), held("uc2", plain, 0, Map.of())),
        List.of(), null, "food.dining", amount, NOW);
    assertThat(fresh.best().userCardId()).as("with headroom, the 5 % card wins").isEqualTo("uc1");
    assertThat(fresh.best().rewardPaise()).isEqualTo(25_000L);
    assertThat(fresh.best().headroomPaise()).as("the whole cap is still available").isEqualTo(50_000L);
    assertThat(fresh.best().capExhausted()).isFalse();

    BestCard.Answer spent = BestCard.rank(
        List.of(held("uc1", rich, 2_000_000L, Map.of("r5", 50_000L)), held("uc2", plain, 0, Map.of())),
        List.of(), null, "food.dining", amount, NOW);
    assertThat(spent.best().userCardId()).as("with the cap used, the 2 % card wins").isEqualTo("uc2");
    assertThat(spent.best().rewardPaise()).isEqualTo(10_000L);
    BestCard.Pick used = spent.picks().stream().filter(p -> p.userCardId().equals("uc1")).findFirst().orElseThrow();
    assertThat(used.rewardPaise()).isZero();
    assertThat(used.capExhausted()).isTrue();
    assertThat(used.headroomPaise()).isZero();
    assertThat(used.reasons()).anyMatch(r -> r.contains("cap on this card is already used"));

    BestCard.Answer partly = BestCard.rank(
        List.of(held("uc1", rich, 1_000_000L, Map.of("r5", 35_000L)), held("uc2", plain, 0, Map.of())),
        List.of(), null, "food.dining", amount, NOW);
    assertThat(partly.best().rewardPaise()).as("only the remaining Rs 150 is earned").isEqualTo(15_000L);
    assertThat(partly.best().userCardId()).as("which still beats 2 %").isEqualTo("uc1");
  }

  // ------------------------------------------------------------------------------- rule selection

  @Test
  void aMerchantRuleBeatsACategoryRule() {
    CardTerms c = card("c", List.of(rule("cat", "food", null, 500, null),
        rule("merch", null, "m-1", 300, null)), null);
    BestCard.Answer here = BestCard.rank(List.of(held("uc", c, 0, Map.of())), List.of(),
        "m-1", "food.dining", 100_000L, NOW);
    assertThat(here.best().ruleId()).isEqualTo("merch");
    assertThat(here.best().rewardPaise()).as("even at a lower rate").isEqualTo(3_000L);

    BestCard.Answer elsewhere = BestCard.rank(List.of(held("uc", c, 0, Map.of())), List.of(),
        "m-2", "food.dining", 100_000L, NOW);
    assertThat(elsewhere.best().ruleId()).isEqualTo("cat");
    assertThat(elsewhere.best().rewardPaise()).isEqualTo(5_000L);
  }

  @Test
  void theExactPathIsUsedSoNoRootShareIsAssumed() {
    CardTerms c = card("c", List.of(rule("broad", "food", null, 200, null),
        rule("deep", "food.dining", null, 700, null)), null);
    BestCard.Answer coffee = BestCard.rank(List.of(held("uc", c, 0, Map.of())), List.of(),
        null, "food.dining.coffee", 100_000L, NOW);
    assertThat(coffee.best().ruleId()).as("food.dining covers food.dining.coffee").isEqualTo("deep");
    assertThat(coffee.best().rewardPaise()).as("the whole 7 %, with no Rules.share approximation (A-81)")
        .isEqualTo(7_000L);

    BestCard.Answer groceries = BestCard.rank(List.of(held("uc", c, 0, Map.of())), List.of(),
        null, "food.groceries", 100_000L, NOW);
    assertThat(groceries.best().ruleId()).as("a sibling path falls back to the broad rule").isEqualTo("broad");
  }

  @Test
  void anExcludedPathEarnsNothing() {
    CardTerms c = card("c", List.of(
        new Rule("r", "food", null, "cashback", 500, 0, null, null, 0, List.of("food.dining"), 0)), null);
    BestCard.Answer a = BestCard.rank(List.of(held("uc", c, 0, Map.of())), List.of(),
        null, "food.dining", 100_000L, NOW);
    assertThat(a.best().rewardPaise()).isZero();
    assertThat(a.best().ruleId()).isNull();
    assertThat(a.best().reasons().get(0)).contains("no reward rule");
  }

  @Test
  void aCardWithNoMatchingRule() {
    CardTerms c = card("c", List.of(rule("t", "travel", null, 500, null)), null);
    BestCard.Answer a = BestCard.rank(List.of(held("uc", c, 0, Map.of())), List.of(),
        null, "food.dining", 100_000L, NOW);
    assertThat(a.best().totalPaise()).isZero();
    assertThat(a.best().headroomPaise()).as("no rule, so no headroom to report").isNull();
  }

  // ------------------------------------------------------------------------------- offers and milestones

  @Test
  void anOfferIsAddedToTheRewardAndReportedSeparately() {
    Offers.Offer pct = new Offers.Offer("o-pct", "c", null, "food", "10 % off", "pct", 1000, null, 20_000L, 0,
        NOW.minusSeconds(60), NOW.plusSeconds(60), false);
    CardTerms c = card("c", List.of(rule("r", "food", null, 100, null)), null);
    BestCard.Answer a = BestCard.rank(List.of(held("uc", c, 0, Map.of())), List.of(pct),
        null, "food.dining", 100_000L, NOW);
    assertThat(a.best().rewardPaise()).isEqualTo(1_000L);
    assertThat(a.best().offerPaise()).isEqualTo(10_000L);
    assertThat(a.best().totalPaise()).isEqualTo(11_000L);
    assertThat(a.best().offers()).hasSize(1);
    assertThat(a.best().reasons()).anyMatch(r -> r.startsWith("offer: "));
  }

  @Test
  void onlyThePurchaseThatCrossesTheMilestoneIsCredited() {
    Milestone ms = new Milestone(1_000_000L, "month", "voucher", 50_000L);
    CardTerms c = card("c", List.of(rule("r", null, null, 100, null)), ms);

    assertThat(BestCard.rank(List.of(held("uc", c, 0, Map.of())), List.of(), null, "food.dining", 100_000L, NOW)
        .best().milestonePaise()).as("far from it, nothing is credited").isZero();
    BestCard.Answer tips = BestCard.rank(List.of(held("uc", c, 950_000L, Map.of())), List.of(),
        null, "food.dining", 100_000L, NOW);
    assertThat(tips.best().milestonePaise()).as("the purchase that crosses it is credited").isEqualTo(50_000L);
    assertThat(tips.best().reasons()).anyMatch(r -> r.contains("milestone"));
    assertThat(BestCard.rank(List.of(held("uc", c, 1_200_000L, Map.of())), List.of(), null, "food.dining",
        100_000L, NOW).best().milestonePaise()).as("already reached, not credited again").isZero();

    assertThat(BestCard.milestoneCredit(ms, 900_000L, 100_000L)).as("exactly at the threshold")
        .isEqualTo(50_000L);
    assertThat(BestCard.milestoneCredit(ms, 899_999L, 100_000L)).as("one paise short").isZero();
  }

  // ------------------------------------------------------------------------------- ordering and edges

  @Test
  void theRankingIsTotalValueThenRewardThenTheCardId() {
    CardTerms a = card("a", List.of(rule("ra", null, null, 300, null)), null);
    CardTerms b = card("b", List.of(rule("rb", null, null, 300, null)), null);
    BestCard.Answer ans = BestCard.rank(
        List.of(held("uc-b", b, 0, Map.of()), held("uc-a", a, 0, Map.of())), List.of(),
        null, "food.dining", 100_000L, NOW);
    assertThat(ans.best().userCardId()).as("a tie breaks on the user card id").isEqualTo("uc-a");
    assertThat(ans.picks()).hasSize(2);
    assertThat(ans.amountPaise()).isEqualTo(100_000L);

    assertThatThrownBy(() -> BestCard.rank(List.of(), List.of(), null, "food", 0, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(BestCard.rank(List.of(), List.of(), null, "food", 100_000L, NOW).best()).isNull();
  }

  @Test
  void theEarnedByRuleFigureIsTheEngineSOwn() {
    // A-85: one rule's cap is shared across roots, so what it has "earned" this month must be read the same way
    CardTerms c = card("c", List.of(rule("r", null, null, 500, 50_000L)), null);
    SpendVector thisMonth = SpendVector.of(Map.of("food", 2_000_000L, "travel", 2_000_000L));
    assertThat(Savings.monthlyEarnedByRule(c, thisMonth))
        .as("the catch-all cap is reached once, not once per root").containsEntry("r", 50_000L);
    assertThat(Savings.monthlyEarnedByRule(c, SpendVector.of(Map.of("food", 200_000L))))
        .as("under the cap, it is the plain 5 %").containsEntry("r", 10_000L);
    assertThat(Savings.monthlyEarnedByRule(c, SpendVector.empty())).isEmpty();
  }
}
