package in.dexter.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Liability instalments on the shared EMI engine (A-74). The reference is the golden fixture MASTER_PLAN §12
 * assigns to P13 — the old {@code budget-service} EmiCalculatorTest's ₹8,884.88 on ₹1,00,000 at 12 % over 12
 * months — which the engine reproduces to the paisa, so planning needs no EMI of its own (ADR-0019).
 */
class LiabilityMathTest {

  @Test
  void theInstalmentMatchesTheBudgetServiceReference() {
    assertThat(LiabilityMath.emi(10_000_000L, 1200, 12)).isEqualTo(888_488L);
    assertThat(LiabilityMath.emi(1_200_000L, 0, 12)).as("zero interest splits the principal").isEqualTo(100_000L);
  }

  @Test
  void theScheduleRepaysThePrincipalExactlyAndClosesAtZero() {
    LiabilityMath.Schedule s = LiabilityMath.schedule(10_000_000L, 1200, 12, LocalDate.parse("2026-02-01"));

    assertThat(s.emiPaise()).isEqualTo(888_488L);
    assertThat(s.installments()).hasSize(12);
    assertThat(s.installments().stream().mapToLong(LiabilityMath.Installment::principalPaise).sum())
        .isEqualTo(10_000_000L);
    assertThat(s.installments().getLast().outstandingAfterPaise()).isZero();
    assertThat(s.totalPayablePaise()).isEqualTo(10_000_000L + s.totalInterestPaise());
    assertThat(s.installments().getFirst().dueOn()).isEqualTo(LocalDate.parse("2026-02-01"));
    assertThat(s.installments().getLast().dueOn()).as("one a month from the start")
        .isEqualTo(LocalDate.parse("2027-01-01"));
  }

  @Test
  void aLoanCarriesNoGstSoEachTotalIsPrincipalPlusInterest() {
    LiabilityMath.Schedule s = LiabilityMath.schedule(5_000_000L, 1800, 6, LocalDate.parse("2026-03-10"));

    for (LiabilityMath.Installment i : s.installments()) {
      assertThat(i.totalPaise()).isEqualTo(i.principalPaise() + i.interestPaise());
    }
    assertThat(s.installments().stream().mapToLong(LiabilityMath.Installment::totalPaise).sum())
        .isEqualTo(s.totalPayablePaise());
  }

  @Test
  void theOutstandingAfterNInstalmentsComesOffTheSchedule() {
    LiabilityMath.Schedule s = LiabilityMath.schedule(10_000_000L, 1200, 12, LocalDate.parse("2026-02-01"));
    List<LiabilityMath.Installment> rows = s.installments();

    assertThat(LiabilityMath.outstandingAfter(s, 0)).isEqualTo(10_000_000L);
    assertThat(LiabilityMath.outstandingAfter(s, 1)).isEqualTo(rows.get(0).outstandingAfterPaise());
    assertThat(LiabilityMath.outstandingAfter(s, 6)).isEqualTo(rows.get(5).outstandingAfterPaise());
    assertThat(LiabilityMath.outstandingAfter(s, 12)).isZero();
    assertThat(LiabilityMath.outstandingAfter(s, 99)).as("past the end is settled").isZero();
  }

  @Test
  void theOutstandingFallsEveryMonth() {
    LiabilityMath.Schedule s = LiabilityMath.schedule(25_000_000L, 2400, 24, LocalDate.parse("2026-12-01"));
    long previous = 25_000_000L;
    for (LiabilityMath.Installment i : s.installments()) {
      assertThat(i.outstandingAfterPaise()).isLessThan(previous);
      previous = i.outstandingAfterPaise();
    }
    assertThat(previous).isZero();
  }
}
