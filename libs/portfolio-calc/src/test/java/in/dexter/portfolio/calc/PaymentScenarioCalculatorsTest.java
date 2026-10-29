package in.dexter.portfolio.calc;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.portfolio.calc.CustomFeeCalculator.AppliesTo;
import in.dexter.portfolio.calc.CustomFeeCalculator.CustomFee;
import in.dexter.portfolio.calc.CustomFeeCalculator.CustomFeesResult;
import in.dexter.portfolio.calc.FullPaymentCalculator.FullPaymentResult;
import in.dexter.portfolio.calc.PartialPaymentCalculator.PartialPaymentResult;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden fixtures ported from the old repo's {@code calc/PaymentScenarioCalculatorsTest} (P12 DoD, ADR-0018): custom
 * fees, the full-payment scenario and the partial-payment scenario. The old references were computed independently
 * in Python/Decimal.
 */
class PaymentScenarioCalculatorsTest {

  private static final LedgerTerms RETAIL_ONLY = new LedgerTerms(4200, 4200, List.of("retail_purchases"));

  private static LocalDate jan(int day) {
    return LocalDate.of(2026, 1, day);
  }

  /** A ₹500 one-off and a 2 % recurring fee. */
  private static List<CustomFee> mixedFees() {
    return List.of(
        CustomFee.flat("f1", "Annual", AppliesTo.ONCE, 50_000L),
        CustomFee.percent("f2", "Service", AppliesTo.EVERY_CYCLE, 200));
  }

  @Test
  void onceFeeOnlyAppliesOnCycleOne() {
    CustomFeesResult cycle1 = CustomFeeCalculator.computeForCycle(mixedFees(), 1_000_000L, 1);
    assertThat(cycle1.totalPaise()).isEqualTo(70_000L); // ₹500 + 2 % of ₹10,000
    assertThat(cycle1.gstPaise()).isEqualTo(12_600L);
    assertThat(cycle1.breakdown()).hasSize(2);

    CustomFeesResult cycle2 = CustomFeeCalculator.computeForCycle(mixedFees(), 1_000_000L, 2);
    assertThat(cycle2.totalPaise()).isEqualTo(20_000L); // the one-off is skipped
    assertThat(cycle2.gstPaise()).isEqualTo(3_600L);
    assertThat(cycle2.breakdown()).hasSize(1);
  }

  @Test
  void noFeesReturnsZeroWithoutError() {
    CustomFeesResult result = CustomFeeCalculator.computeForCycle(List.of(), 1_000_000L, 1);

    assertThat(result.totalPaise()).isZero();
    assertThat(result.gstPaise()).isZero();
    assertThat(result.breakdown()).isEmpty();
  }

  @Test
  void fullPaymentIsFreeExceptForConfiguredCustomFees() {
    List<CustomFee> onceFee = List.of(CustomFee.flat("f1", "Annual", AppliesTo.ONCE, 50_000L));

    FullPaymentResult result = FullPaymentCalculator.simulate(500_000L, onceFee);

    assertThat(result.paymentPaise()).isEqualTo(500_000L);
    assertThat(result.customFeesPaise()).isEqualTo(50_000L);
    assertThat(result.gstOnCustomFeesPaise()).isEqualTo(9_000L);
    assertThat(result.totalCostPaise()).isEqualTo(559_000L);
    assertThat(result.gracePreservedNextCycle()).isTrue();
  }

  @Test
  void fullPaymentWithNoFeesIsExactlyZeroExtraCost() {
    FullPaymentResult result = FullPaymentCalculator.simulate(300_000L, List.of());

    assertThat(result.totalCostPaise()).isEqualTo(300_000L);
    assertThat(result.verdict()).contains("Zero extra cost");
  }

  @Test
  void partialPaymentCarriesOnlyTheLotPrincipalNotTheStatementBalanceMinusPayment() {
    // The original's own slightly surprising accounting, ported faithfully: the waterfall runs against the purchase
    // lots only (a cycle's closing lots never include an interest lot), so the carried principal is
    // purchase-lot-minus-payment, not statement-balance-minus-payment.
    PartialPaymentResult result = PartialPaymentCalculator.simulate(RETAIL_ONLY,
        List.of(LedgerTransaction.purchase(jan(1), 500_000L)), jan(1), jan(30), 200_000L, false, List.of(), null,
        null);

    assertThat(result.scenario()).isEqualTo("partial_payment");
    assertThat(result.statementBalancePaise()).isEqualTo(517_260L); // ₹5,172.60
    assertThat(result.interestChargedThisCyclePaise()).isEqualTo(17_260L); // ₹172.60
    assertThat(result.gstOnInterestThisCyclePaise()).isEqualTo(3_107L); // ₹31.07
    assertThat(result.carriedPrincipalIntoNextCyclePaise()).isEqualTo(300_000L); // ₹3,000, not ₹3,172.60
    assertThat(result.gracePreservedNextCycle()).isFalse();
    assertThat(result.nextCycleInterestIfNoFurtherPaymentPaise()).isNull(); // no next-cycle window supplied
  }

  @Test
  void paymentCoveringTheFullStatementDefersToFullPaymentInstead() {
    PartialPaymentResult result = PartialPaymentCalculator.simulate(RETAIL_ONLY,
        List.of(LedgerTransaction.purchase(jan(1), 100_000L)), jan(1), jan(5), 99_999_900L, false, List.of(), null,
        null);

    assertThat(result.scenario()).isEqualTo("full_payment_instead");
    assertThat(result.gracePreservedNextCycle()).isTrue();
  }

  @Test
  void reRatesGraceZeroedLotsBeforeProjectingNextCycleInterest() {
    // A purchase during a grace-active cycle has apr = 0 baked in. The payment falls short, so grace is lost and the
    // carried lot must be re-rated to the real APR — otherwise next-cycle interest would wrongly come out as zero.
    PartialPaymentResult result = PartialPaymentCalculator.simulate(RETAIL_ONLY,
        List.of(LedgerTransaction.purchase(jan(1), 500_000L)), jan(1), jan(30), 100_000L, true, List.of(), jan(31),
        LocalDate.of(2026, 2, 27));

    assertThat(result.nextCycleInterestIfNoFurtherPaymentPaise()).isNotNull();
    assertThat(result.nextCycleInterestIfNoFurtherPaymentPaise()).isPositive();
  }
}
