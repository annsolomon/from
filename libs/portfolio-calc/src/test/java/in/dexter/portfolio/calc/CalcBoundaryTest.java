package in.dexter.portfolio.calc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.portfolio.calc.MinimumDueCalculator.FormulaType;
import in.dexter.portfolio.calc.MinimumDueCalculator.MinimumDueInputs;
import in.dexter.portfolio.calc.MinimumDueCalculator.MinimumDueTerms;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The paise boundary itself, which is the one thing this port adds rather than carries over (ADR-0018), plus the
 * input validation that replaces the original's null dereferences. Nothing here is a ported fixture.
 */
class CalcBoundaryTest {

  @Test
  void paiseRoundTripExactlyAndRupeeConversionNeverRounds() {
    for (long paise : new long[] {0L, 1L, 99L, 100L, 123_456_789L, Long.MAX_VALUE / 1000}) {
      assertThat(Money.paise(Money.rupees(paise))).isEqualTo(paise);
    }
    assertThat(Money.rupees(1L)).isEqualByComparingTo("0.01");
    assertThat(Money.rupees(-250L)).isEqualByComparingTo("-2.50");
  }

  @Test
  void round2OnRupeesIsRoundingToThePaisa() {
    assertThat(Money.paise(new BigDecimal("1.005"))).isEqualTo(101L); // HALF_UP, as the originals rounded
    assertThat(Money.paise(new BigDecimal("1.004"))).isEqualTo(100L);
  }

  @Test
  void bpsAndPercentConvertExactly() {
    assertThat(Money.percent(4200)).isEqualByComparingTo("42");
    assertThat(Money.percent(250)).isEqualByComparingTo("2.5");
    assertThat(Money.bps(new BigDecimal("120.00"))).isEqualTo(12_000);
    assertThat(Money.bps(new BigDecimal("0.01"))).isEqualTo(1);
  }

  @Test
  void minimumDueTermsRejectTheFieldsTheirFormulaNeeds() {
    assertThatThrownBy(() -> new MinimumDueTerms(FormulaType.FLAT, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("flatAmount");
    assertThatThrownBy(() -> new MinimumDueTerms(FormulaType.HIGHER_OF, null, 500, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MinimumDueTerms(FormulaType.FINANCE_CHARGE_CONDITIONAL, null, null, 100L, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aConfiguredCreditLimitOfZeroIsRejectedRatherThanDividedBy() {
    // The original would have divided by zero computing utilization.
    assertThatThrownBy(() -> CreditUtilizationCalculator.utilization(100L, 0L))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("greater than zero");
  }

  @Test
  void emptyAllocationOrderAndBackwardsCyclesAreRejected() {
    assertThatThrownBy(() -> new LedgerTerms(4200, 4200, List.of()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paymentAllocationOrder");
    assertThatThrownBy(() -> LedgerEngine.ledger(List.of(),
        new LedgerTerms(4200, 4200, List.of("retail_purchases")), java.time.LocalDate.of(2026, 1, 31),
        java.time.LocalDate.of(2026, 1, 1), List.of(), false))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycleEnd");
  }

  @Test
  void aNegativeTransactionAmountIsRejected() {
    assertThatThrownBy(() -> LedgerTransaction.purchase(java.time.LocalDate.of(2026, 1, 1), -1L))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("negative");
  }

  @Test
  void flatMinimumDueIsTheFlatAmountWhateverIsOutstanding() {
    MinimumDueTerms flat = MinimumDueTerms.flat(20_000L);

    assertThat(MinimumDueCalculator.calculate(flat, MinimumDueInputs.of(0L))).isEqualTo(20_000L);
    assertThat(MinimumDueCalculator.calculate(flat, MinimumDueInputs.of(10_000_000L))).isEqualTo(20_000L);
  }

  @Test
  void percentOfOutstandingIsFlooredAtItsFlatFloor() {
    MinimumDueTerms terms = MinimumDueTerms.percentOfOutstanding(500, 20_000L);

    assertThat(MinimumDueCalculator.calculate(terms, MinimumDueInputs.of(1_000_000L))).isEqualTo(50_000L);
    // 5 % of ₹100 is ₹5, below the ₹200 floor
    assertThat(MinimumDueCalculator.calculate(terms, MinimumDueInputs.of(10_000L))).isEqualTo(20_000L);
  }
}
