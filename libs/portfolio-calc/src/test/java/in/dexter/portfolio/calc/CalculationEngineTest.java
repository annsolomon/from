package in.dexter.portfolio.calc;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.portfolio.calc.CreditUtilizationCalculator.OverlimitFeeTerms;
import in.dexter.portfolio.calc.CreditUtilizationCalculator.UtilizationResult;
import in.dexter.portfolio.calc.LateFeeAndDelinquencyCalculator.DelinquencyClassification;
import in.dexter.portfolio.calc.LateFeeAndDelinquencyCalculator.DelinquencyStage;
import in.dexter.portfolio.calc.LateFeeAndDelinquencyCalculator.LateFeeTier;
import in.dexter.portfolio.calc.MinimumDueCalculator.MinimumDueInputs;
import in.dexter.portfolio.calc.MinimumDueCalculator.MinimumDueTerms;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden fixtures ported from the old repo's {@code calc/CalculationEngineTest} (P12 DoD, ADR-0018): the minimum-due
 * formulas, the late-fee tiers and the RBI staging, and credit utilization. Every expected value is that test's own
 * assertion in paise, and its references came from the ported source's own worked example
 * ({@code regaliaGold.example.js}'s tier table and {@code finance_charge_conditional} terms).
 */
class CalculationEngineTest {

  /** The example card's own {@code finance_charge_conditional} terms: ₹200 floor, 5 %, rounded up to ₹10. */
  private static MinimumDueTerms regaliaGoldMinimumDue() {
    return MinimumDueTerms.financeChargeConditional(20_000L, 500, 1_000L);
  }

  /** The example card's own late-fee schedule, in paise. */
  private static List<LateFeeTier> regaliaGoldTiers() {
    return List.of(
        new LateFeeTier(0L, 10_000L, 0L),
        new LateFeeTier(10_100L, 50_000L, 10_000L),
        new LateFeeTier(50_100L, 100_000L, 50_000L),
        new LateFeeTier(100_100L, 500_000L, 60_000L),
        new LateFeeTier(500_100L, 1_000_000L, 75_000L),
        new LateFeeTier(1_000_100L, 2_500_000L, 90_000L),
        new LateFeeTier(2_500_100L, 5_000_000L, 110_000L),
        new LateFeeTier(5_000_100L, null, 130_000L));
  }

  // ---- MinimumDueCalculator

  @Test
  void financeChargeConditionalBlendedRateWins() {
    MinimumDueInputs inputs = new MinimumDueInputs(800_000L, 15_000L, 0L, 0L, 2_700L, 0L, 817_700L);

    // blended = roundUpToNearest(5 % × (₹8,000 + ₹150), ₹10) = roundUpToNearest(₹407.50, ₹10) = ₹410,
    // which beats the ₹150 finance charge, so the due is ₹27 GST + ₹410 = ₹437
    assertThat(MinimumDueCalculator.calculate(regaliaGoldMinimumDue(), inputs)).isEqualTo(43_700L);
  }

  @Test
  void financeChargeConditionalUnderFlatFloorPaysItOffInFull() {
    MinimumDueInputs inputs = new MinimumDueInputs(15_000L, 0L, 0L, 0L, 0L, 0L, 15_000L);

    // total due at or below the ₹200 floor: pay it in full rather than minimum-due it
    assertThat(MinimumDueCalculator.calculate(regaliaGoldMinimumDue(), inputs)).isEqualTo(15_000L);
  }

  @Test
  void higherOfPicksThePercentWhenItBeatsTheFlat() {
    MinimumDueTerms terms = MinimumDueTerms.higherOf(500, 20_000L);

    // 5 % of ₹10,000 is ₹500, which beats the ₹200 flat
    assertThat(MinimumDueCalculator.calculate(terms, MinimumDueInputs.of(1_000_000L))).isEqualTo(50_000L);
  }

  @Test
  void roundUpToNearestMatchesCeilSemantics() {
    assertThat(MinimumDueCalculator.roundUpToNearestPaise(40_750L, 1_000L)).isEqualTo(41_000L);
    // already an exact multiple: unchanged
    assertThat(MinimumDueCalculator.roundUpToNearestPaise(40_000L, 1_000L)).isEqualTo(40_000L);
  }

  // ---- LateFeeAndDelinquencyCalculator

  @Test
  void lateFeeTierLookupMatchesRegaliaGoldSchedule() {
    // ₹3,200 falls in the ₹1,001–₹5,000 tier
    assertThat(LateFeeAndDelinquencyCalculator.lateFee(320_000L, regaliaGoldTiers())).isEqualTo(60_000L);
    // ₹75,000 lands in the uncapped top tier
    assertThat(LateFeeAndDelinquencyCalculator.lateFee(7_500_000L, regaliaGoldTiers())).isEqualTo(130_000L);
    // nothing outstanding short-circuits before any tier lookup
    assertThat(LateFeeAndDelinquencyCalculator.lateFee(0L, regaliaGoldTiers())).isZero();
  }

  @Test
  void delinquencyStagesMatchRbiSmaThresholds() {
    assertStage(0, DelinquencyStage.CURRENT, false, false);
    assertStage(15, DelinquencyStage.SMA_0, true, false);
    assertStage(45, DelinquencyStage.SMA_1, true, false);
    assertStage(75, DelinquencyStage.SMA_2, true, false);
    assertStage(91, DelinquencyStage.NPA, true, true);
  }

  private void assertStage(int daysOverdue, DelinquencyStage expected, boolean reported, boolean severe) {
    DelinquencyClassification c = LateFeeAndDelinquencyCalculator.classifyDelinquencyStage(daysOverdue);
    assertThat(c.stage()).isEqualTo(expected);
    assertThat(c.reportedToBureaus()).isEqualTo(reported);
    assertThat(c.severelyDamaging()).isEqualTo(severe);
  }

  // ---- CreditUtilizationCalculator

  @Test
  void utilizationAndOverlimitMatchHandComputedExample() {
    UtilizationResult result = CreditUtilizationCalculator.utilization(12_000_000L, 10_000_000L);

    assertThat(result.creditLimitConfigured()).isTrue();
    assertThat(result.overLimitPaise()).isEqualTo(2_000_000L);
    assertThat(result.overLimit()).isTrue();
    assertThat(result.availableCreditPaise()).isZero();
    assertThat(result.utilizationBps()).isEqualTo(12_000); // 120.00 %

    // 2.5 % of ₹20,000 is ₹500, under the ₹550 flat minimum, so higher-of picks ₹550
    long fee = CreditUtilizationCalculator.overlimitFee(result.overLimitPaise(), new OverlimitFeeTerms(250, 55_000L));
    assertThat(fee).isEqualTo(55_000L);
  }

  @Test
  void noConfiguredLimitMeansNoUtilizationOrOverlimit() {
    UtilizationResult result = CreditUtilizationCalculator.utilization(500_000L, null);

    assertThat(result.creditLimitConfigured()).isFalse();
    assertThat(result.overLimit()).isFalse();
    assertThat(result.overLimitPaise()).isZero();
    assertThat(result.utilizationBps()).isNull();
  }
}
