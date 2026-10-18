package in.dexter.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Field-level diff against the published document and its application on approve (pure). */
class CardDiffTest {
  static final UUID MERCHANT = UUID.fromString("0192a5a1-0000-7000-8000-00000000beef");

  static CardDocument published() {
    return new CardDocument("mock-card", "Mock Card", "MOCK_BANK", "visa", "entry", 50_000L, 50_000L, 10_000_000L,
        4200, 350, 100, Map.of(), List.of(
            new CardDocument.Rule(null, null, null, "cashback", 100, 0L, null, null, 0L, List.of("fuel"), 10),
            new CardDocument.Rule("food.delivery", null, null, "cashback", 500, 0L, 100_000L, "statement", 0L,
                List.of(), 110),
            new CardDocument.Rule("food.delivery", MERCHANT, "swiggy", "cashback", 1000, 0L, null, null, 0L,
                List.of(), 200)),
        null);
  }

  static CardExtractor.Extraction page(String html) {
    return CardExtractor.extract(html);
  }

  @Test
  void anUnchangedPageHasNoChanges() {
    String html = "<h1>Mock Card</h1><p>Joining fee: ₹500</p><p>Annual fee: ₹500</p>"
        + "<p>Annual fee waived on spends of ₹1 lakh</p><p>Forex markup: 3.5%</p><p>Interest 3.5% per month</p>"
        + "<p>1% fuel surcharge waiver</p><p>5% cashback on food delivery. 1% cashback on all other spends</p>";
    assertThat(CardDiff.compute(published(), page(html))).isEmpty();
  }

  @Test
  void aChangedFeeIsOneChangeAndAbsentFieldsAreNotRemovals() {
    List<CardDiff.Change> changes = CardDiff.compute(published(), page("<h1>Mock Card</h1><p>Annual fee: ₹999</p>"));
    assertThat(changes).singleElement().satisfies(c -> {
      assertThat(c.path()).isEqualTo("annualFeePaise");
      assertThat(c.from()).isEqualTo(50_000L);
      assertThat(c.to()).isEqualTo(99_900L);
      assertThat(c.confidence()).isEqualTo(1.0);
    });
    CardDocument applied = CardDiff.apply(published(), changes);
    assertThat(applied.annualFeePaise()).isEqualTo(99_900L);
    assertThat(applied.joiningFeePaise()).as("not stated on the page: kept").isEqualTo(50_000L);
    assertThat(applied.rules()).isEqualTo(published().rules());
  }

  @Test
  void ruleChangesKeepCapsAndNeverTouchMerchantRules() {
    List<CardDiff.Change> changes = CardDiff.compute(published(), page(
        "<p>7% cashback on Swiggy and Zomato. 2% cashback on dining. 1% cashback on all other spends.</p>"));
    assertThat(changes).extracting(CardDiff.Change::path)
        .containsExactly("rules/food.delivery", "rules/food.dining");
    assertThat(changes.get(0).from()).isEqualTo(Map.of("rewardType", "cashback", "rateBps", 500));
    assertThat(changes.get(0).to()).isEqualTo(Map.of("rewardType", "cashback", "rateBps", 700));
    assertThat(changes.get(1).from()).as("a new category rule").isNull();

    CardDocument applied = CardDiff.apply(published(), changes);
    assertThat(applied.rules()).hasSize(4);
    CardDocument.Rule delivery = applied.rules().get(1);
    assertThat(delivery.rateBps()).isEqualTo(700);
    assertThat(delivery.capPaise()).as("cap kept").isEqualTo(100_000L);
    assertThat(delivery.priority()).isEqualTo(110);
    assertThat(applied.rules().get(2)).as("merchant rule untouched").isEqualTo(published().rules().get(2));
    CardDocument.Rule dining = applied.rules().get(3);
    assertThat(dining.categoryPath()).isEqualTo("food.dining");
    assertThat(dining.rateBps()).isEqualTo(200);
    assertThat(dining.priority()).isEqualTo(100);
    assertThat(CardDocuments.validate(applied)).isEmpty();
  }

  @Test
  void aBaseRateChangeAndAPointsRuleBecomingCashback() {
    CardDocument points = new CardDocument("p", "P", "B", "visa", "entry", 0L, 0L, null, null, null, null, Map.of(),
        List.of(new CardDocument.Rule(null, null, null, "points", 200, 250L, null, null, 0L, List.of(), 10)), null);
    List<CardDiff.Change> changes = CardDiff.compute(points, page("<p>1.5% cashback on all spends</p>"));
    assertThat(changes).singleElement().satisfies(c -> {
      assertThat(c.path()).isEqualTo(CardDiff.BASE_RULE);
      assertThat(c.from()).isEqualTo(Map.of("rewardType", "points", "rateBps", 200));
    });
    CardDocument.Rule base = CardDiff.apply(points, changes).rules().getFirst();
    assertThat(base.rewardType()).isEqualTo("cashback");
    assertThat(base.rateBps()).isEqualTo(150);
    assertThat(base.pointValueMilliPaise()).isZero();
  }
}
