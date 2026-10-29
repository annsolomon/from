package in.dexter.portfolio.calc;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.portfolio.calc.MinimumDueCalculator.MinimumDueTerms;
import in.dexter.portfolio.calc.MinimumPaymentSimulator.MinimumPaymentResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden fixtures ported from the old repo's {@code calc/MinimumPaymentSimulatorTest} (P12 DoD, ADR-0018) —
 * including the 141-cycle case the plan calls out by name (MASTER_PLAN P12 tests). The old references were computed
 * independently in Python as a full cycle-by-cycle Decimal simulation, and this port reproduces all three totals to
 * the paisa.
 */
class MinimumPaymentSimulatorTest {

  @Test
  void higherOfMinimumEventuallyClearsAFlatBalance() {
    LedgerTerms terms = new LedgerTerms(4200, 4200, List.of("retail_purchases", "finance_charges", "fees"));
    MinimumDueTerms minimumDue = MinimumDueTerms.higherOf(500, 20_000L);

    MinimumPaymentResult result =
        MinimumPaymentSimulator.simulate(terms, minimumDue, 1_000_000L, 30, 360, null, null);

    assertThat(result.infiniteDebtWarning()).isFalse();
    assertThat(result.cyclesToPayoff()).isEqualTo(141);
    assertThat(result.totalInterestPaidPaise()).isEqualTo(2_611_942L); // ₹26,119.42
    assertThat(result.totalGstPaidPaise()).isEqualTo(470_146L); // ₹4,701.46
    assertThat(result.totalExtraPaidPaise()).isEqualTo(3_082_088L); // ₹30,820.88
    assertThat(result.amortizationTable()).hasSize(141);
  }

  @Test
  void flatMinimumBelowInterestNeverClears() {
    // A pathologically weak minimum-due formula: a flat ₹100 a cycle against a balance whose interest alone is far
    // more than that every cycle.
    LedgerTerms terms = new LedgerTerms(4200, 4200, List.of("retail_purchases", "finance_charges"));
    MinimumDueTerms minimumDue = MinimumDueTerms.flat(10_000L);

    MinimumPaymentResult result =
        MinimumPaymentSimulator.simulate(terms, minimumDue, 5_000_000L, 30, 360, null, null);

    assertThat(result.infiniteDebtWarning()).isTrue();
    assertThat(result.cyclesToPayoff()).isNull();
    assertThat(result.totalExtraPaidPaise()).isNull();
    // caught on cycle 1 rather than running out 360 cycles of a known-divergent trajectory
    assertThat(result.amortizationTable()).hasSize(1);
    assertThat(result.amortizationTable().get(0).infiniteDebtWarningRow()).isTrue();
  }
}
