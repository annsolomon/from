package in.dexter.portfolio.calc;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.portfolio.calc.RepaymentPathCalculator.BestPathResult;
import in.dexter.portfolio.calc.RepaymentPathCalculator.CardBalance;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden fixtures ported from the old repo's {@code calc/RepaymentPathCalculatorTest} (P12 DoD, ADR-0018). The old
 * references were computed by translating the same algorithm into Python and running it there; this port reproduces
 * both strategies' interest and the saving to the paisa.
 */
class RepaymentPathCalculatorTest {

  private static List<CardBalance> twoCards() {
    return List.of(
        new CardBalance("a", "A", 1_000_000L, 4200, 50_000L),
        new CardBalance("b", "B", 500_000L, 1800, 25_000L));
  }

  @Test
  void avalancheBeatsSnowballWhenAprGapIsLarge() {
    BestPathResult result = RepaymentPathCalculator.findBestRepaymentPath(twoCards(), 300_000L);

    assertThat(result.feasible()).isTrue();
    assertThat(result.recommendedStrategy()).isEqualTo("avalanche");
    assertThat(result.avalanche().monthsToDebtFree()).isEqualTo(6);
    assertThat(result.avalanche().totalInterestPaidPaise()).isEqualTo(149_911L); // ₹1,499.11
    assertThat(result.snowball().monthsToDebtFree()).isEqualTo(6);
    assertThat(result.snowball().totalInterestPaidPaise()).isEqualTo(194_361L); // ₹1,943.61
    assertThat(result.interestSavingsWithBestStrategyPaise()).isEqualTo(44_450L); // ₹444.50
  }

  @Test
  void infeasibleWhenBudgetBelowTotalMinimumDue() {
    BestPathResult result = RepaymentPathCalculator.findBestRepaymentPath(twoCards(), 70_000L);

    assertThat(result.feasible()).isFalse();
    assertThat(result.message()).contains("less than total minimum due");
    assertThat(result.avalanche()).isNull();
  }

  @Test
  void avalancheOrdersByHighestAprFirstRegardlessOfInputOrder() {
    List<CardBalance> cards = List.of(
        new CardBalance("lo", "LowApr", 100_000L, 1000, 10_000L),
        new CardBalance("hi", "HighApr", 100_000L, 4000, 10_000L));

    BestPathResult result = RepaymentPathCalculator.findBestRepaymentPath(cards, 50_000L);

    assertThat(result.avalanche().paymentOrder()).containsExactly("HighApr", "LowApr");
  }
}
