package in.dexter.portfolio.calc;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.portfolio.calc.LedgerEngine.LedgerResult;
import in.dexter.portfolio.calc.LedgerEngine.LotView;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden fixtures ported from the old repo's {@code calc/ledger/LedgerEngineTest} (P12 DoD, ADR-0018). Every
 * expected value is that test's own assertion in paise — ₹345.21 becomes 34_521 — and the old values were computed
 * independently in Python's Decimal (exact day-by-day accrual), not read back out of any implementation.
 */
class LedgerEngineTest {

  private static final LedgerTerms TERMS =
      new LedgerTerms(4200, 4200, List.of("retail_purchases", "cash_advances"));

  private static LocalDate jan(int day) {
    return LocalDate.of(2026, 1, day);
  }

  @Test
  void staticBalanceAccruesSimpleDailyInterestOverTheWholeCycle() {
    LotView opening = new LotView(jan(1), "retail_purchases", 1_000_000L, 4200, null);

    LedgerResult result = LedgerEngine.ledger(List.of(), TERMS, jan(1), jan(30), List.of(opening), false);

    // 30 days at ₹10,000 × 42 % ÷ 365 per day
    assertThat(result.totalInterestPaise()).isEqualTo(34_521L);
    assertThat(result.closingLots()).hasSize(1);
    assertThat(result.closingLots().get(0).remainingPaise()).isEqualTo(1_000_000L);
  }

  @Test
  void purchaseThenPaymentMidCycleSplitsInterestAtTheCorrectBalance() {
    LedgerTransaction purchase =
        new LedgerTransaction(jan(1), TransactionType.PURCHASE, 500_000L, "retail_purchases", "TXN-1", null);
    LedgerTransaction payment = LedgerTransaction.payment(jan(10), 200_000L);

    LedgerResult result = LedgerEngine.ledger(List.of(purchase, payment), TERMS, jan(1), jan(30), List.of(), false);

    // 9 days at ₹5,000, then 21 at ₹3,000 — a payment applies before that day's interest accrues
    assertThat(result.totalInterestPaise()).isEqualTo(12_427L);
    assertThat(result.closingLots()).hasSize(1);
    assertThat(result.closingLots().get(0).remainingPaise()).isEqualTo(300_000L);
  }

  @Test
  void graceActiveMeansZeroAprOnNewRetailPurchaseLots() {
    LedgerResult result = LedgerEngine.ledger(List.of(LedgerTransaction.purchase(jan(1), 500_000L)), TERMS, jan(1),
        jan(30), List.of(), true);

    assertThat(result.totalInterestPaise()).isZero();
  }

  @Test
  void cashAdvancesAlwaysAccrueAtTheCashAdvanceAprRegardlessOfGrace() {
    LedgerTerms differentRates = new LedgerTerms(4200, 4800, List.of("cash_advances", "retail_purchases"));
    LedgerTransaction cashAdvance =
        new LedgerTransaction(jan(1), TransactionType.PURCHASE, 100_000L, "cash_advances", null, null);

    LedgerResult result =
        LedgerEngine.ledger(List.of(cashAdvance), differentRates, jan(1), jan(1), List.of(), true);

    // grace would zero a retail lot's APR; cash advances never get grace
    assertThat(result.totalInterestPaise()).isPositive();
  }

  @Test
  void refundReversesTheSpecificOriginalLotNotTheGenericWaterfall() {
    LedgerTransaction purchaseA =
        new LedgerTransaction(jan(1), TransactionType.PURCHASE, 300_000L, "retail_purchases", "ORIG-A", null);
    LedgerTransaction purchaseB =
        new LedgerTransaction(jan(1), TransactionType.PURCHASE, 200_000L, "retail_purchases", "ORIG-B", null);
    LedgerTransaction refund =
        new LedgerTransaction(jan(2), TransactionType.REFUND_SPECIFIC, 300_000L, null, null, "ORIG-A");

    LedgerResult result =
        LedgerEngine.ledger(List.of(purchaseA, purchaseB, refund), TERMS, jan(1), jan(2), List.of(), false);

    // ORIG-A fully reversed and ORIG-B untouched — not "₹3,000 paid down across whatever the waterfall picks"
    assertThat(result.closingLots()).hasSize(1);
    assertThat(result.closingLots().get(0).referenceNumber()).isEqualTo("ORIG-B");
    assertThat(result.closingLots().get(0).remainingPaise()).isEqualTo(200_000L);
  }
}
