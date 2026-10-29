package in.dexter.portfolio.calc;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.portfolio.calc.LateFeeAndDelinquencyCalculator.DelinquencyStage;
import in.dexter.portfolio.calc.LateFeeAndDelinquencyCalculator.LateFeeTier;
import in.dexter.portfolio.calc.NoPaymentSimulator.NoPaymentResult;
import in.dexter.portfolio.calc.NoPaymentSimulator.Projection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden fixtures ported from the old repo's {@code calc/NoPaymentSimulatorTest} (P12 DoD, ADR-0018). The old
 * references were computed independently in Python/Decimal; this port reproduces the interest and the total
 * outstanding at 30, 60 and 90 days to the paisa.
 */
class NoPaymentSimulatorTest {

  private static final LedgerTerms TERMS = new LedgerTerms(4200, 4200, List.of("retail_purchases"));

  /** The example card's schedule up to the ₹5,001–₹10,000 tier, in paise. */
  private static List<LateFeeTier> regaliaGoldTiers() {
    return List.of(
        new LateFeeTier(0L, 10_000L, 0L),
        new LateFeeTier(10_100L, 50_000L, 10_000L),
        new LateFeeTier(50_100L, 100_000L, 50_000L),
        new LateFeeTier(100_100L, 500_000L, 60_000L),
        new LateFeeTier(500_100L, 1_000_000L, 75_000L));
  }

  @Test
  void projectsInterestAndStageCorrectlyAt30And60And90Days() {
    NoPaymentResult result =
        NoPaymentSimulator.simulate(TERMS, regaliaGoldTiers(), null, 500_000L, null, 30);

    assertThat(result.lateFeePaise()).isEqualTo(60_000L); // ₹600
    assertThat(result.projections()).hasSize(3);

    Projection at30 = result.projections().get(0);
    assertThat(at30.daysOverdue()).isEqualTo(30);
    assertThat(at30.interestAccruedPaise()).isEqualTo(17_260L); // ₹172.60
    assertThat(at30.totalOutstandingPaise()).isEqualTo(591_167L); // ₹5,911.67
    assertThat(at30.delinquencyStage().stage()).isEqualTo(DelinquencyStage.SMA_0);

    Projection at60 = result.projections().get(1);
    assertThat(at60.interestAccruedPaise()).isEqualTo(34_521L); // ₹345.21
    assertThat(at60.totalOutstandingPaise()).isEqualTo(611_535L); // ₹6,115.35
    assertThat(at60.delinquencyStage().stage()).isEqualTo(DelinquencyStage.SMA_1);

    Projection at90 = result.projections().get(2);
    assertThat(at90.interestAccruedPaise()).isEqualTo(51_781L); // ₹517.81
    assertThat(at90.totalOutstandingPaise()).isEqualTo(631_902L); // ₹6,319.02
    assertThat(at90.delinquencyStage().stage()).isEqualTo(DelinquencyStage.SMA_2);
  }

  @Test
  void projectionsAreMonotonicallyIncreasing() {
    NoPaymentResult result =
        NoPaymentSimulator.simulate(TERMS, regaliaGoldTiers(), null, 200_000L, null, 30);

    List<Projection> p = result.projections();
    assertThat(p.get(0).totalOutstandingPaise()).isLessThan(p.get(1).totalOutstandingPaise());
    assertThat(p.get(1).totalOutstandingPaise()).isLessThan(p.get(2).totalOutstandingPaise());
  }
}
