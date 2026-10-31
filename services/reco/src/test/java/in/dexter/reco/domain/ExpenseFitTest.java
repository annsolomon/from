package in.dexter.reco.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** "Is this card aimed at me", the 40 % of the blend that is not rupees (MASTER_PLAN P14). */
class ExpenseFitTest {

  static CardTerms card(String id, String path, int rateBps) {
    return new CardTerms(id, id, id, "HDFC", 0, null,
        List.of(new CardTerms.Rule("r", path, null, "cashback", rateBps, 0, null, null, 0, List.of(), 0)), null);
  }

  @Test
  void aCardAimedAtTheAccountsSpendFitsPerfectly() {
    assertThat(ExpenseFit.bps(card("food-card", "food", 500), SpendVector.of(Map.of("food", 1_000_000L))))
        .isEqualTo(10_000);
  }

  @Test
  void aCardAimedElsewhereDoesNotFit() {
    assertThat(ExpenseFit.bps(card("travel-card", "travel", 500), SpendVector.of(Map.of("food", 1_000_000L))))
        .isZero();
  }

  @Test
  void aMostlyMatchingCardFitsInProportion() {
    Map<String, Long> m = new LinkedHashMap<>();
    m.put("food", 2_000_000L);        // 80 % of the spend
    m.put("travel", 500_000L);        // 20 %
    int fit = ExpenseFit.bps(card("food-card", "food", 500), SpendVector.of(m));
    assertThat(fit).as("the food card earns on four fifths of this spend").isBetween(7_000, 9_000);
  }

  @Test
  void aFlatCardFitsEveryoneEqually() {
    Map<String, Long> m = new LinkedHashMap<>();
    m.put("food", 2_000_000L);
    m.put("travel", 500_000L);
    // a flat rate earns everywhere, so its strongest reward is where the money goes by definition
    assertThat(ExpenseFit.bps(card("flat", null, 100), SpendVector.of(m))).isEqualTo(10_000);
  }

  @Test
  void noSpendMeansNoFit() {
    assertThat(ExpenseFit.bps(card("food-card", "food", 500), SpendVector.empty())).isZero();
  }

  @Test
  void aCardThatEarnsNothingAnywhereHasNoFit() {
    assertThat(ExpenseFit.bps(card("zero", "food", 0), SpendVector.of(Map.of("food", 1_000_000L)))).isZero();
  }

  @Test
  void theRewardVectorIsTheValueRatePerRoot() {
    Map<String, Long> m = new LinkedHashMap<>();
    m.put("food", 1_000_000L);
    m.put("travel", 1_000_000L);
    Map<String, Integer> v = ExpenseFit.rewardVector(card("food-card", "food", 500), SpendVector.of(m));
    assertThat(v.get("food")).isEqualTo(500);
    assertThat(v.get("travel")).isZero();
  }
}
