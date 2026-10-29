package in.dexter.portfolio.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.List;

/**
 * Cross-card payoff strategy: avalanche (highest APR first) against snowball (smallest balance first) (old repo
 * {@code calc/RepaymentPathCalculator}, itself a port of {@code repaymentPathEngine.js}).
 *
 * <p>Deliberately simpler than the per-card ledger engine, per the original's own scope note: it takes each card's
 * flat APR, balance and minimum due rather than full calculation terms, because comparing "attack the highest APR or
 * the smallest balance" does not need the fee-and-lot machinery. It uses the same daily-periodic-rate compounding
 * formula, applied to a flat balance instead of a lot-by-lot ledger — the original notes this matches HDFC's
 * published illustration exactly.
 *
 * <p>Each month: every card accrues 30 days of compounded interest plus GST, then pays its minimum due as the budget
 * allows; whatever budget is left goes to the first card still owing in the strategy's order.
 */
public final class RepaymentPathCalculator {

  private static final MathContext MC = new MathContext(20);
  private static final int MAX_MONTHS = 360;
  private static final int DAYS_PER_MONTH = 30;

  private RepaymentPathCalculator() {}

  public record CardBalance(String id, String name, long balancePaise, int aprBps, long minimumDuePaise) {}

  public record StrategyResult(int monthsToDebtFree, long totalInterestPaidPaise, List<String> paymentOrder,
      String description) {}

  /** Everything but {@code feasible} and {@code message} is null when the budget cannot cover the minimums. */
  public record BestPathResult(boolean feasible, String message, String recommendedStrategy, StrategyResult avalanche,
      StrategyResult snowball, Long interestSavingsWithBestStrategyPaise, String verdict) {}

  public static BestPathResult findBestRepaymentPath(List<CardBalance> cards, long monthlyBudgetPaise) {
    if (cards == null || cards.isEmpty()) {
      throw new IllegalArgumentException("at least one card is required");
    }
    long totalMinimum = cards.stream().mapToLong(CardBalance::minimumDuePaise).sum();
    if (monthlyBudgetPaise < totalMinimum) {
      return new BestPathResult(false,
          "Monthly budget ₹%s is less than total minimum due ₹%s. Consider increasing your payment budget."
              .formatted(Money.rs(Money.rupees(monthlyBudgetPaise)), Money.rs(Money.rupees(totalMinimum))),
          null, null, null, null, null);
    }

    List<CardBalance> avalancheOrder = cards.stream()
        .sorted(Comparator.comparingInt(CardBalance::aprBps).reversed()).toList();
    List<CardBalance> snowballOrder = cards.stream()
        .sorted(Comparator.comparingLong(CardBalance::balancePaise)).toList();

    StrategyResult avalanche = withDescription(simulate(avalancheOrder, monthlyBudgetPaise),
        "Pay highest APR card first — minimizes total interest paid");
    StrategyResult snowball = withDescription(simulate(snowballOrder, monthlyBudgetPaise),
        "Pay smallest balance first — faster psychological wins");

    boolean avalancheWins = avalanche.totalInterestPaidPaise() <= snowball.totalInterestPaidPaise();
    long savings = Math.abs(avalanche.totalInterestPaidPaise() - snowball.totalInterestPaidPaise());

    String verdict = "%s method saves you ₹%s in interest. %s".formatted(
        avalancheWins ? "Avalanche" : "Snowball", Money.rs(Money.rupees(savings)),
        avalancheWins ? "Focus extra payments on your highest APR card."
            : "Clear your smallest balance card first for momentum.");

    return new BestPathResult(true, null, avalancheWins ? "avalanche" : "snowball", avalanche, snowball, savings,
        verdict);
  }

  private static StrategyResult simulate(List<CardBalance> orderedCards, long monthlyBudgetPaise) {
    List<Running> cards = orderedCards.stream().map(Running::new).toList();
    BigDecimal monthlyBudget = Money.rupees(monthlyBudgetPaise);
    int month = 0;
    BigDecimal totalInterestPaid = BigDecimal.ZERO;

    while (cards.stream().anyMatch(Running::owing) && month < MAX_MONTHS) {
      month++;
      BigDecimal remainingBudget = monthlyBudget;

      for (Running card : cards) {
        if (!card.owing()) {
          continue;
        }
        BigDecimal interest = compoundedInterest(card.remaining, card.aprPercent, DAYS_PER_MONTH);
        BigDecimal gst = Money.round2(interest.multiply(Money.GST_RATE, MC));
        card.remaining = Money.round2(card.remaining.add(interest).add(gst));
        totalInterestPaid = Money.round2(totalInterestPaid.add(interest).add(gst));

        BigDecimal payment = card.minimumDue.min(remainingBudget).min(card.remaining);
        card.remaining = Money.round2(card.remaining.subtract(payment));
        remainingBudget = Money.round2(remainingBudget.subtract(payment));
      }

      for (Running card : cards) {
        if (!card.owing() || remainingBudget.compareTo(BigDecimal.ZERO) <= 0) {
          continue;
        }
        BigDecimal extra = remainingBudget.min(card.remaining);
        card.remaining = Money.round2(card.remaining.subtract(extra));
        remainingBudget = Money.round2(remainingBudget.subtract(extra));
        break; // the surplus goes to the first card still owing, in this strategy's order
      }
    }

    return new StrategyResult(month, Money.paise(totalInterestPaid),
        orderedCards.stream().map(CardBalance::name).toList(), null);
  }

  /** The daily-periodic-rate compounding the full ledger engine uses, on a flat balance: {@code P·((1+dpr)^d − 1)}. */
  private static BigDecimal compoundedInterest(BigDecimal principal, BigDecimal aprPercent, int days) {
    if (principal.compareTo(BigDecimal.ZERO) <= 0 || days <= 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal dpr = aprPercent.divide(Money.HUNDRED, MC).divide(BigDecimal.valueOf(365), MC);
    BigDecimal factor = BigDecimal.ONE.add(dpr).pow(days, MC);
    return principal.multiply(factor.subtract(BigDecimal.ONE, MC), MC);
  }

  private static StrategyResult withDescription(StrategyResult r, String description) {
    return new StrategyResult(r.monthsToDebtFree(), r.totalInterestPaidPaise(), r.paymentOrder(), description);
  }

  /** One card's running balance through a strategy. */
  private static final class Running {
    final BigDecimal aprPercent;
    final BigDecimal minimumDue;
    BigDecimal remaining;

    Running(CardBalance c) {
      this.aprPercent = Money.percent(c.aprBps());
      this.minimumDue = Money.rupees(c.minimumDuePaise());
      this.remaining = Money.rupees(c.balancePaise());
    }

    boolean owing() {
      return remaining.compareTo(BigDecimal.ZERO) > 0;
    }
  }
}
