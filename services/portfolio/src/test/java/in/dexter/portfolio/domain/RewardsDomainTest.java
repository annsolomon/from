package in.dexter.portfolio.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reward rule selection, earning and the FIFO points balance (pure). */
class RewardsDomainTest {
  static final UUID SWIGGY = UUID.fromString("0192a5a1-0000-7000-8000-0000000005e1");
  static final RewardTerms TERMS = new RewardTerms(List.of(
      new RewardTerms.Rule(null, null, "points", 133, 25_000, 0, List.of("fuel", "housing.rent"), 10),
      new RewardTerms.Rule("food", null, "points", 333, 25_000, 0, List.of(), 100),
      new RewardTerms.Rule("food.delivery", null, "points", 666, 25_000, 10_000, List.of(), 101),
      new RewardTerms.Rule("food.delivery", SWIGGY, "points", 1000, 25_000, 0, List.of(), 200)));

  @Test
  void theMostSpecificRuleApplies() {
    assertThat(TERMS.ruleFor("food.delivery.pizza", null).rateBps()).isEqualTo(666);
    assertThat(TERMS.ruleFor("food.dining", null).rateBps()).isEqualTo(333);
    assertThat(TERMS.ruleFor("travel", null).rateBps()).isEqualTo(133);
    assertThat(TERMS.ruleFor(null, null).rateBps()).as("uncategorised: the base rate").isEqualTo(133);
    assertThat(TERMS.ruleFor("fuel.petrol", null)).as("excluded").isNull();
    assertThat(TERMS.ruleFor("housing.rent", null)).isNull();
    assertThat(TERMS.ruleFor("food.delivery", SWIGGY).rateBps()).as("merchant rule first").isEqualTo(1000);
  }

  @Test
  void earningInPointsAndCashback() {
    RewardTerms.Earning e = TERMS.earn(75_000, "food.delivery", null); // Rs 750 at 6.66 % = Rs 49.95 = 199.8 points
    assertThat(e.points()).isEqualTo(199);
    assertThat(e.valueMilliPaise()).isEqualTo(199 * 25_000L);
    assertThat(e.valuePaise()).isEqualTo(4_975);
    assertThat(TERMS.earn(5_000, "food.delivery", null).rateBps()).as("below the minimum spend: the rule is skipped")
        .isZero();
    assertThat(TERMS.earn(100_000, "fuel", null)).isEqualTo(RewardTerms.Earning.NONE);
    RewardTerms cash = new RewardTerms(List.of(new RewardTerms.Rule(null, null, "cashback", 150, 0, 0, List.of(), 10)));
    RewardTerms.Earning c = cash.earn(64_900, "shopping", null);
    assertThat(c.points()).isZero();
    assertThat(c.valuePaise()).isEqualTo(973); // 1.5 % of Rs 649 = Rs 9.735 (milli-paise kept, paise floored)
  }

  @Test
  void pointsAreConsumedOldestExpiryFirst() {
    LocalDate today = LocalDate.parse("2026-10-22");
    PointsBalance.Result r = PointsBalance.of(List.of(
        new PointsBalance.Entry("earn", 100, LocalDate.parse("2026-11-01")),
        new PointsBalance.Entry("earn", 50, LocalDate.parse("2028-01-01")),
        new PointsBalance.Entry("redeem", -30, null),
        new PointsBalance.Entry("earn", 20, LocalDate.parse("2026-10-01")),
        new PointsBalance.Entry("adjust", 5, null)), today, 60);
    // redeem 30 takes the lot that already lapsed (20) first, then 10 of the Nov lot
    assertThat(r.available()).isEqualTo(145);
    assertThat(r.lapsed()).isZero();
    assertThat(r.expiringSoon()).isEqualTo(90);
    assertThat(r.soon()).containsExactly(new PointsBalance.Expiring(LocalDate.parse("2026-11-01"), 90));
    PointsBalance.Result lapsed = PointsBalance.of(List.of(
        new PointsBalance.Entry("earn", 40, LocalDate.parse("2026-10-01"))), today, 60);
    assertThat(lapsed.available()).isZero();
    assertThat(lapsed.lapsed()).isEqualTo(40);
  }
}
