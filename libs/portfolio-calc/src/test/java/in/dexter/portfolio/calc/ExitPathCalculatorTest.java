package in.dexter.portfolio.calc;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.portfolio.calc.ExitPathCalculator.FoundFlatPayment;
import in.dexter.portfolio.calc.ExitPathCalculator.SafeExitPathsResult;
import in.dexter.portfolio.calc.ExitPathCalculator.TargetedPayoffPath;
import in.dexter.portfolio.calc.MinimumDueCalculator.MinimumDueTerms;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden fixtures ported from the old repo's {@code calc/ExitPathCalculatorTest} (P12 DoD, ADR-0018) — the
 * binary-search scenario the plan calls out by name (MASTER_PLAN P12 tests). The old reference was computed by
 * translating {@code findMinimumFlatPaymentForCycles} into Python/Decimal and running it there; this port returns
 * the same ₹2,141.01 payment and the same interest and GST to the paisa.
 */
class ExitPathCalculatorTest {

  private static final LedgerTerms TERMS =
      new LedgerTerms(3600, 3600, List.of("finance_charges", "retail_purchases"));

  private static MinimumDueTerms minimumDueTerms() {
    return MinimumDueTerms.higherOf(500, 20_000L);
  }

  @Test
  void findMinimumFlatPaymentMatchesIndependentBinarySearch() {
    FoundFlatPayment found = ExitPathCalculator.findMinimumFlatPaymentForCycles(TERMS, 600_000L, 3, 30);

    assertThat(found).isNotNull();
    assertThat(found.paymentPaise()).isEqualTo(214_101L); // ₹2,141.01
    assertThat(found.clearedByCycle()).isEqualTo(3);
    assertThat(found.totalInterestPaise()).isEqualTo(35_915L); // ₹359.15
    assertThat(found.totalGstPaise()).isEqualTo(6_465L); // ₹64.65
  }

  @Test
  void aPaymentBelowTheFoundMinimumFailsToClearWithinTheTargetCycles() {
    // The search returns the smallest payment that clears in 3 cycles, so meaningfully less must fail — proof the
    // bracket converged tightly rather than settling anywhere feasible.
    var underShooting = ExitPathCalculator.simulateFlatPayment(TERMS, 600_000L, 150_000L, 3, 30);

    assertThat(underShooting.feasible()).isFalse();
    assertThat(underShooting.clearedByCycle()).isNull();
  }

  @Test
  void zeroBalanceHasNothingToExitFrom() {
    SafeExitPathsResult result =
        ExitPathCalculator.computeSafeExitPaths(TERMS, minimumDueTerms(), 0L, 30, List.of(3, 6, 12));

    assertThat(result.feasible()).isTrue();
    assertThat(result.targetedPaths()).isEmpty();
    assertThat(result.message()).contains("already zero");
  }

  @Test
  void everyTargetedPathIsCheaperThanPayingOnlyTheMinimum() {
    SafeExitPathsResult result =
        ExitPathCalculator.computeSafeExitPaths(TERMS, minimumDueTerms(), 600_000L, 30, List.of(3, 6, 12));

    assertThat(result.feasible()).isTrue();
    assertThat(result.payInFullNow().amountRequiredPaise()).isEqualTo(600_000L);
    assertThat(result.targetedPaths()).hasSize(3);
    assertThat(result.continueMinimum().feasible()).isTrue();
    assertThat(result.recommendedPath()).isEqualTo("targeted_payoff_3cycles");

    for (TargetedPayoffPath path : result.targetedPaths()) {
      assertThat(path.feasible()).isTrue();
      // The structural invariant straight from the original's selling point: stretching a payoff out is never
      // cheaper than paying it faster.
      assertThat(path.totalInterestAndGstPaise()).isLessThan(result.continueMinimum().totalExtraPaidPaise());
    }
    assertThat(result.targetedPaths().get(0).totalInterestAndGstPaise())
        .isLessThan(result.targetedPaths().get(2).totalInterestAndGstPaise());
  }
}
