package in.dexter.reco.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The 60/40 blend, the ranking order and the not-recommended bucket (MASTER_PLAN P14). */
class ScorerTest {

  static CardTerms.Rule rule(String id, String path, int rateBps) {
    return new CardTerms.Rule(id, path, null, "cashback", rateBps, 0, null, null, 0, List.of(), 0);
  }

  static CardTerms card(String id, String slug, long feePaise, Long waiver, CardTerms.Rule... rules) {
    return new CardTerms(id, slug, "Card " + slug, "HDFC", feePaise, waiver, List.of(rules), null);
  }

  static SpendVector spend() {
    Map<String, Long> m = new LinkedHashMap<>();
    m.put("food", 2_000_000L);
    m.put("travel", 500_000L);
    return SpendVector.of(m);
  }

  static List<String> ids(List<Scorer.Scored> l) {
    return l.stream().map(Scorer.Scored::cardProductId).toList();
  }

  @Test
  void theBlendIsSixtyFortyAndConfigurable() {
    CardTerms good = card("p1", "good", 0, null, rule("food", "food", 500));
    CardTerms weak = card("p2", "weak", 0, null, rule("flat", null, 100));
    Scorer.Ranking blended = Scorer.rank(List.of(good, weak), spend(), Set.of());
    assertThat(blended.savingsWeightBps()).isEqualTo(6_000);
    assertThat(blended.thresholdBps()).isEqualTo(2_000);

    Scorer.Scored top = blended.recommended().get(0);
    assertThat(top.scoreBps())
        .as("the blend is savings x 0.6 + fit x 0.4")
        .isEqualTo((top.savingsBps() * 6_000 + top.fitBps() * 4_000) / 10_000);

    Scorer.Ranking allSavings = Scorer.rank(List.of(good, weak), spend(), Set.of(), 10_000, 0);
    assertThat(allSavings.recommended().get(0).scoreBps())
        .as("with all the weight on savings the best card scores the maximum").isEqualTo(10_000);
    Scorer.Ranking allFit = Scorer.rank(List.of(good, weak), spend(), Set.of(), 0, 0);
    assertThat(allFit.recommended().get(0).scoreBps()).isEqualTo(allFit.recommended().get(0).fitBps());
  }

  @Test
  void savingsAreNormalisedAgainstTheBestCardInTheSet() {
    CardTerms best = card("p1", "best", 0, null, rule("food", "food", 500));
    CardTerms half = card("p2", "half", 0, null, rule("food", "food", 250));
    Scorer.Ranking r = Scorer.rank(List.of(best, half), spend(), Set.of());
    Scorer.Scored b = r.recommended().stream().filter(s -> s.cardProductId().equals("p1")).findFirst().orElseThrow();
    Scorer.Scored h = r.recommended().stream().filter(s -> s.cardProductId().equals("p2")).findFirst().orElseThrow();
    assertThat(b.savingsBps()).isEqualTo(10_000);
    assertThat(h.savingsBps()).as("half the rupees, half the normalised savings").isBetween(4_500, 5_500);
  }

  @Test
  void aCardThatLosesMoneyIsNeverRecommended() {
    CardTerms pricey = card("p2", "pricey", 1_000_000L, null, rule("flat", null, 100));
    CardTerms good = card("p1", "good", 0, null, rule("food", "food", 500));
    Scorer.Ranking r = Scorer.rank(List.of(good, pricey), spend(), Set.of());
    assertThat(ids(r.recommended())).containsExactly("p1");
    assertThat(ids(r.notRecommended())).containsExactly("p2");
    assertThat(r.recommended()).allMatch(s -> s.annualSavingsPaise() > 0);
  }

  @Test
  void theRankingDoesNotDependOnTheInputOrder() {
    CardTerms a = card("p1", "a", 0, null, rule("food", "food", 500));
    CardTerms b = card("p2", "b", 0, null, rule("travel", "travel", 900));
    CardTerms c = card("p3", "c", 0, null, rule("flat", null, 200));
    Scorer.Ranking one = Scorer.rank(List.of(a, b, c), spend(), Set.of());
    Scorer.Ranking two = Scorer.rank(List.of(c, b, a), spend(), Set.of());
    assertThat(ids(one.recommended())).isEqualTo(ids(two.recommended()));
    assertThat(ids(one.notRecommended())).isEqualTo(ids(two.notRecommended()));
  }

  @Test
  void scoresDescendAndHeldCardsAreMarked() {
    CardTerms a = card("p1", "a", 0, null, rule("food", "food", 500));
    CardTerms b = card("p2", "b", 0, null, rule("flat", null, 150));
    Scorer.Ranking r = Scorer.rank(List.of(a, b), spend(), Set.of("p1"));
    for (int i = 1; i < r.recommended().size(); i++) {
      assertThat(r.recommended().get(i - 1).scoreBps()).isGreaterThanOrEqualTo(r.recommended().get(i).scoreBps());
    }
    assertThat(r.recommended().stream().filter(s -> s.cardProductId().equals("p1")).findFirst().orElseThrow()
        .held()).isTrue();
    assertThat(r.recommended().stream().filter(s -> s.cardProductId().equals("p2")).findFirst().orElseThrow()
        .held()).isFalse();
  }

  @Test
  void aColdAccountRecommendsNothingRatherThanGuessing() {
    CardTerms a = card("p1", "a", 0, null, rule("food", "food", 500));
    CardTerms b = card("p2", "b", 100_000L, null, rule("flat", null, 150));
    Scorer.Ranking r = Scorer.rank(List.of(a, b), SpendVector.empty(), Set.of());
    assertThat(r.recommended()).isEmpty();
    assertThat(r.notRecommended()).hasSize(2);
    assertThat(r.notRecommended()).allMatch(s -> !s.reasons().isEmpty(), "every card still says why");
  }

  @Test
  void everyScoredCardCarriesItsReasonsAndItsBreakdown() {
    CardTerms a = card("p1", "a", 100_000L, 30_000_000L, rule("food", "food", 500));
    Scorer.Scored s = Scorer.rank(List.of(a), spend(), Set.of()).recommended().get(0);
    assertThat(s.reasons()).isNotEmpty();
    assertThat(s.savings().lines()).isNotEmpty();
    assertThat(s.rewardVector()).containsKeys("food", "travel");
    assertThat(s.reasons()).anyMatch(t -> t.contains("food"));
  }

  @Test
  void anOutOfRangeWeightIsRefused() {
    CardTerms a = card("p1", "a", 0, null, rule("food", "food", 500));
    assertThat(org.assertj.core.api.Assertions.catchThrowable(
        () -> Scorer.rank(List.of(a), spend(), Set.of(), 10_001, 0)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
