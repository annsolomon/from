package in.dexter.portfolio.calc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Pay part of the statement: interest on what is carried, and grace lost for the next cycle (old repo
 * {@code calc/PartialPaymentCalculator}, itself a port of {@code simulators.js simulatePartialPayment}).
 *
 * <p>The trickiest of the four scenarios, per the original's own load-bearing note: a lot created during a
 * grace-active cycle has {@code apr = 0} baked in at creation time, but once grace is confirmed lost (this cycle's
 * payment fell short of the full statement) any surviving lot from that cycle must be <em>re-rated</em> to the real
 * APR before it rolls into the next cycle — a lot's APR does not track the cycle-level grace state changing after
 * the lot was created. Cash-advance lots are untouched: they never had grace.
 *
 * <p>Also carried over faithfully: the waterfall runs against the purchase lots only (a cycle's closing lots never
 * include an interest lot), so the carried principal is purchase-lots-minus-payment, not
 * statement-balance-minus-payment.
 */
public final class PartialPaymentCalculator {

  private PartialPaymentCalculator() {}

  /**
   * @param scenario {@code partial_payment}, or {@code full_payment_instead} when the payment covers the whole
   *     statement — the caller should then use {@link FullPaymentCalculator} (the original's own delegation,
   *     surfaced here as a distinct result rather than a hidden branch)
   * @param nextCycleInterestIfNoFurtherPaymentPaise null when no next-cycle window was supplied
   */
  public record PartialPaymentResult(String scenario, long paymentPaise, long statementBalancePaise,
      long interestChargedThisCyclePaise, long gstOnInterestThisCyclePaise, long carriedPrincipalIntoNextCyclePaise,
      boolean gracePreservedNextCycle, Long nextCycleInterestIfNoFurtherPaymentPaise, String verdict) {}

  /** The cycle being paid, and optionally the next cycle to project forward into. */
  public static PartialPaymentResult simulate(LedgerTerms terms, List<LedgerTransaction> cycleTransactions,
      LocalDate cycleStart, LocalDate cycleEnd, long paymentPaise, boolean graceActiveThisCycle,
      List<LedgerTransaction> nextCycleTransactions, LocalDate nextCycleStart, LocalDate nextCycleEnd) {

    LedgerEngine.Accrual thisCycle =
        LedgerEngine.accrue(cycleTransactions, terms, cycleStart, cycleEnd, List.of(), graceActiveThisCycle);

    BigDecimal purchaseTotal = cycleTransactions.stream()
        .filter(t -> t.type() == TransactionType.PURCHASE)
        .map(t -> Money.rupees(t.amountPaise()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal statementBalance = Money.round2(purchaseTotal.add(thisCycle.totalInterest()));
    BigDecimal payment = Money.rupees(paymentPaise);

    if (payment.compareTo(statementBalance) >= 0) {
      return new PartialPaymentResult("full_payment_instead", Money.paise(payment), Money.paise(statementBalance),
          0L, 0L, 0L, true, null, "Payment covers the full statement — use the full-payment result instead.");
    }

    List<Lot> carryLots = new ArrayList<>();
    for (Lot l : thisCycle.closingLots()) {
      carryLots.add(l.copy());
    }
    LedgerEngine.applyPaymentWaterfall(carryLots, payment, terms.paymentAllocationOrder());
    BigDecimal carriedPrincipal = Money.round2(LedgerEngine.sumRemaining(carryLots));
    BigDecimal gstOnInterest = Money.gst(thisCycle.totalInterest());

    BigDecimal nextCycleInterest = null;
    if (nextCycleStart != null && nextCycleEnd != null) {
      BigDecimal retailApr = Money.percent(terms.aprBps());
      List<Lot> openingLots = carryLots.stream()
          .filter(Lot::open)
          .map(l -> "cash_advances".equals(l.bucket) ? l
              : new Lot(l.date, l.bucket, l.remaining, retailApr, l.referenceNumber))
          .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
      openingLots.add(new Lot(cycleEnd, "finance_charges", thisCycle.totalInterest(), retailApr, null));

      nextCycleInterest = LedgerEngine.accrue(
          nextCycleTransactions == null ? List.of() : nextCycleTransactions,
          terms, nextCycleStart, nextCycleEnd, openingLots, false).totalInterest();
    }

    String verdict = "Carrying ₹%s forward costs ₹%s interest this cycle"
        .formatted(Money.rs(carriedPrincipal), Money.rs(thisCycle.totalInterest()))
        + (nextCycleInterest != null
            ? ", and because grace is lost, next cycle's NEW purchases start accruing interest from day 1 — "
                + "projected next-cycle interest: ₹%s.".formatted(Money.rs(nextCycleInterest))
            : ".");

    return new PartialPaymentResult("partial_payment", Money.paise(payment), Money.paise(statementBalance),
        Money.paise(thisCycle.totalInterest()), Money.paise(gstOnInterest), Money.paise(carriedPrincipal), false,
        Money.paiseOrNull(nextCycleInterest), verdict);
  }
}
