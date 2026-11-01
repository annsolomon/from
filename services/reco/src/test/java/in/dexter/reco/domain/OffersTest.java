package in.dexter.reco.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a catalog offer is worth on one purchase, and which offers may be taken together (MASTER_PLAN P15). */
class OffersTest {
  static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  static Offers.Offer offer(String id, String card, String merchant, String path, String kind, Integer bps,
      Long flat, Long maxDiscount, long minSpend, boolean stackable) {
    return new Offers.Offer(id, card, merchant, path, "Offer " + id, kind, bps, flat, maxDiscount, minSpend,
        NOW.minusSeconds(3600), NOW.plusSeconds(3600), stackable);
  }

  @Test
  void aPercentageOfferIsBoundedByItsCeiling() {
    Offers.Offer pct = offer("o-pct", "c", null, "food", "pct", 1000, null, 20_000L, 0, false);
    assertThat(pct.valueOn(100_000L)).as("10 % of Rs 1,000").isEqualTo(10_000L);
    assertThat(pct.valueOn(500_000L)).as("but never past the Rs 200 ceiling").isEqualTo(20_000L);
  }

  @Test
  void aFlatOfferNeedsItsMinimumSpendAndNeverExceedsThePurchase() {
    Offers.Offer flat = offer("o-flat", "c", null, null, "flat", null, 15_000L, null, 100_000L, false);
    assertThat(flat.appliesTo(null, "food.dining", 50_000L, NOW)).as("below the minimum").isFalse();
    assertThat(flat.appliesTo(null, "food.dining", 100_000L, NOW)).as("at the minimum").isTrue();
    assertThat(flat.valueOn(200_000L)).isEqualTo(15_000L);
    assertThat(flat.valueOn(10_000L)).as("an offer cannot hand back more than was spent").isEqualTo(10_000L);
  }

  @Test
  void validityAndScopeAreBothChecked() {
    Offers.Offer o = new Offers.Offer("o", "c", "m-1", "food", "T", "pct", 500, null, null, 0,
        NOW.plusSeconds(60), NOW.plusSeconds(600), false);
    assertThat(o.appliesTo("m-1", "food.dining", 100_000L, NOW)).as("not yet valid").isFalse();
    assertThat(o.appliesTo("m-1", "food.dining", 100_000L, NOW.plusSeconds(120))).isTrue();
    assertThat(o.appliesTo("m-1", "food.dining", 100_000L, NOW.plusSeconds(600)))
        .as("validTo is exclusive").isFalse();
    assertThat(o.appliesTo("m-2", "food.dining", 100_000L, NOW.plusSeconds(120))).as("wrong merchant").isFalse();
    assertThat(o.appliesTo("m-1", "travel.air", 100_000L, NOW.plusSeconds(120))).as("wrong category").isFalse();
  }

  @Test
  void aCategoryOfferCoversThePathAndEverythingUnderIt() {
    assertThat(Offers.covers("food", "food.dining")).isTrue();
    assertThat(Offers.covers("food", "food")).isTrue();
    assertThat(Offers.covers("food", "foodie")).as("a label is not a prefix").isFalse();
    assertThat(Offers.covers(null, "anything")).as("no category means every category").isTrue();
  }

  @Test
  void oneNonStackableOfferPlusEveryStackableOne() {
    Offers.Offer big = offer("o-big", "c", null, null, "flat", null, 30_000L, null, 0, false);
    Offers.Offer small = offer("o-small", "c", null, null, "flat", null, 10_000L, null, 0, false);
    Offers.Offer stack1 = offer("o-s1", "c", null, null, "flat", null, 5_000L, null, 0, true);
    Offers.Offer stack2 = offer("o-s2", "c", null, null, "flat", null, 2_000L, null, 0, true);

    Offers.Applied applied = Offers.bestFor(List.of(small, big, stack1, stack2), "c", null, "food.dining",
        500_000L, NOW);
    assertThat(applied.totalPaise()).isEqualTo(37_000L);
    assertThat(applied.offers()).hasSize(3);
    assertThat(applied.offers()).as("the weaker exclusive offer is left out")
        .noneMatch(o -> o.offerId().equals("o-small"));
  }

  @Test
  void anotherCardsOffersDoNotApplyAndNothingExceedsThePurchase() {
    Offers.Offer big = offer("o-big", "c", null, null, "flat", null, 30_000L, null, 0, false);
    assertThat(Offers.bestFor(List.of(big), "other-card", null, "food.dining", 500_000L, NOW).totalPaise())
        .isZero();
    assertThat(Offers.bestFor(List.of(big), "c", null, "food.dining", 20_000L, NOW).totalPaise())
        .isEqualTo(20_000L);
    assertThat(Offers.bestFor(List.of(), "c", null, "food.dining", 20_000L, NOW).offers()).isEmpty();
  }
}
