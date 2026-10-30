package in.dexter.portfolio.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Reducing-balance EMI with GST on interest and the processing fee (pure, exact). Written in P11 for portfolio and
 * moved here with the engine in P13 (A-74), plus the old repo's {@code budget-service} golden fixtures, which
 * MASTER_PLAN §12 assigns to P13 and which this engine satisfies unchanged.
 */
class EmiMathTest {

  @Test
  void matchesTheBudgetServiceReferenceOfEightThousandEightHundredAndEightyFour() {
    // Golden fixture ported from the old repo's budget-service EmiCalculatorTest (MASTER_PLAN §12, P13):
    // Rs 1,00,000 at 12 % a year over 12 months is the textbook case, independently verified there as
    // EMI = Rs 8,884.88. This engine returns it to the paisa, so planning needs no EMI of its own (ADR-0019).
    assertThat(EmiMath.emi(10_000_000, 1200, 12)).isEqualTo(888_488);

    // its second case: zero interest splits the principal evenly
    assertThat(EmiMath.emi(1_200_000, 0, 12)).isEqualTo(100_000);
  }

  @Test
  void aStandardEmiMatchesThePublishedFormula() {
    // Rs 1,00,000 at 15 % a year for 12 months: EMI Rs 9,025.83, interest Rs 8,309.97
    EmiMath.Schedule s = EmiMath.schedule(10_000_000, 1500, 12, 1800, 19_900, LocalDate.parse("2026-11-05"));
    assertThat(s.emiPaise()).isEqualTo(902_583);
    assertThat(s.totalInterestPaise()).isEqualTo(830_997);
    assertThat(s.installments()).hasSize(12);
    assertThat(s.installments().stream().mapToLong(EmiMath.Installment::principalPaise).sum())
        .as("principal repaid exactly").isEqualTo(10_000_000);
    EmiMath.Installment first = s.installments().getFirst();
    assertThat(first.interestPaise()).as("1.25 % of 1,00,000").isEqualTo(125_000);
    assertThat(first.gstPaise()).as("18 % of interest + fee").isEqualTo(Math.round((125_000 + 19_900) * 0.18));
    assertThat(first.totalPaise()).isEqualTo(first.principalPaise() + 125_000 + 19_900 + first.gstPaise());
    EmiMath.Installment last = s.installments().getLast();
    assertThat(last.outstandingAfterPaise()).isZero();
    assertThat(last.dueDate()).isEqualTo(LocalDate.parse("2027-10-05"));
    assertThat(s.totalGstPaise()).isEqualTo(s.installments().stream().mapToLong(EmiMath.Installment::gstPaise).sum());
  }

  @Test
  void zeroCostEmiSplitsThePrincipal() {
    EmiMath.Schedule s = EmiMath.schedule(1_000_000, 0, 3, 1800, 0, LocalDate.parse("2026-01-31"));
    assertThat(s.emiPaise()).isEqualTo(333_333);
    assertThat(s.installments()).extracting(EmiMath.Installment::principalPaise)
        .containsExactly(333_333L, 333_333L, 333_334L);
    assertThat(s.totalInterestPaise()).isZero();
    assertThat(s.installments().get(1).dueDate()).as("month-end clamps").isEqualTo(LocalDate.parse("2026-02-28"));
  }

  @Test
  void outstandingFallsEveryMonth() {
    EmiMath.Schedule s = EmiMath.schedule(25_000_000, 2400, 24, 1800, 0, LocalDate.parse("2026-12-01"));
    long prev = 25_000_000;
    for (EmiMath.Installment i : s.installments()) {
      assertThat(i.outstandingAfterPaise()).isLessThan(prev);
      assertThat(i.principalPaise() + i.interestPaise()).as("EMI except the last").isBetween(s.emiPaise() - 2,
          s.emiPaise() + 2_000);
      prev = i.outstandingAfterPaise();
    }
    assertThat(prev).isZero();
  }
}
