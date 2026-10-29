package in.dexter.portfolio.calc;

import in.dexter.portfolio.calc.CreditUtilizationCalculator.OverlimitFeeTerms;
import in.dexter.portfolio.calc.MinimumDueCalculator.MinimumDueInputs;
import in.dexter.portfolio.calc.MinimumDueCalculator.MinimumDueTerms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Cycle by cycle: if this account only ever pays the minimum due, how many billing cycles until it clears and what
 * does that cost (old repo {@code calc/MinimumPaymentSimulator}, itself a port of
 * {@code simulators.js simulateMinimumPayment}). This is the "continue minimum only" baseline
 * {@link ExitPathCalculator} compares against.
 *
 * <p>Each cycle's unpaid interest (with its GST) becomes a {@code finance_charges} lot, and an over-limit fee its own
 * {@code fees} lot, so next cycle's interest accrues on them too — that is where compounding across cycles comes
 * from.
 *
 * <p>When the minimum due does not even cover the cycle's charges the balance grows without bound; the simulation
 * says so on cycle 1 rather than running out a known-divergent trajectory.
 *
 * <p>Custom fees are not folded in, as in the old port: a card with configured custom fees simulates a lower total
 * cost here than the real one. Stated, not silently absorbed (BUILD_README §7). {@link CustomFeeCalculator} exists in
 * this library and can be charged per cycle by a caller that wants them.
 */
public final class MinimumPaymentSimulator {

  /** The anchor the original used; only the day counts matter. */
  static final LocalDate SIMULATION_EPOCH = LocalDate.of(2026, 1, 1);

  private MinimumPaymentSimulator() {}

  /**
   * One row of the amortization table.
   *
   * @param paymentPaise null on the warning row — no payment is simulated once the balance is known to diverge
   * @param closingPrincipalPaise null on the warning row
   */
  public record CycleRow(int cycle, long openingPrincipalPaise, long financeChargesPaise, long gstPaise,
      long overlimitFeePaise, long overLimitPaise, long minimumDuePaise, Long paymentPaise,
      Long closingPrincipalPaise, boolean infiniteDebtWarningRow) {}

  /** Every total is null when {@code infiniteDebtWarning} is set: there is no payoff to total up. */
  public record MinimumPaymentResult(long startingPrincipalPaise, Integer cyclesToPayoff, boolean infiniteDebtWarning,
      Long totalInterestPaidPaise, Long totalGstPaidPaise, Long totalOverlimitFeesPaidPaise, Long totalExtraPaidPaise,
      List<CycleRow> amortizationTable, String verdict) {}

  /**
   * @param maxCycles a hard stop on the simulation (the original used 360 from the exit-path engine)
   * @param creditLimitPaise null when the card has no configured limit
   * @param overlimitFeeTerms null when no over-limit fee applies
   */
  public static MinimumPaymentResult simulate(LedgerTerms terms, MinimumDueTerms minimumDueTerms,
      long startingPrincipalPaise, int cycleLengthDays, int maxCycles, Long creditLimitPaise,
      OverlimitFeeTerms overlimitFeeTerms) {
    if (cycleLengthDays < 1 || maxCycles < 1) {
      throw new IllegalArgumentException("cycleLengthDays and maxCycles must be at least 1");
    }
    BigDecimal retailApr = Money.percent(terms.aprBps());
    List<Lot> openingLots = new ArrayList<>(List.of(
        new Lot(SIMULATION_EPOCH, "retail_purchases", Money.rupees(startingPrincipalPaise), retailApr, null)));
    List<CycleRow> table = new ArrayList<>();
    int cycleNum = 0;
    LocalDate cycleStartDate = SIMULATION_EPOCH;

    while (LedgerEngine.sumRemaining(openingLots).compareTo(BigDecimal.ZERO) > 0 && cycleNum < maxCycles) {
      cycleNum++;
      LocalDate cycleStart = cycleStartDate;
      LocalDate cycleEnd = cycleStartDate.plusDays(cycleLengthDays - 1L);

      BigDecimal openingPrincipal = Money.round2(LedgerEngine.sumRemaining(openingLots));
      BigDecimal financeCharges =
          LedgerEngine.accrue(List.of(), terms, cycleStart, cycleEnd, openingLots, false).totalInterest();
      BigDecimal gst = Money.gst(financeCharges);

      BigDecimal overLimit = BigDecimal.ZERO;
      BigDecimal overlimitFee = BigDecimal.ZERO;
      if (creditLimitPaise != null) {
        var utilization = CreditUtilizationCalculator.utilization(Money.paise(openingPrincipal), creditLimitPaise);
        overLimit = Money.rupees(utilization.overLimitPaise());
        if (overLimit.compareTo(BigDecimal.ZERO) > 0 && overlimitFeeTerms != null) {
          overlimitFee = CreditUtilizationCalculator.fee(overLimit, overlimitFeeTerms);
        }
      }
      BigDecimal overlimitFeeGst = Money.gst(overlimitFee);

      BigDecimal totalOwed = Money.round2(
          openingPrincipal.add(financeCharges).add(gst).add(overlimitFee).add(overlimitFeeGst));
      MinimumDueInputs inputs = new MinimumDueInputs(Money.paise(openingPrincipal), Money.paise(financeCharges), 0L,
          Money.paise(overlimitFee), Money.paise(Money.round2(gst.add(overlimitFeeGst))), Money.paise(overLimit),
          Money.paise(totalOwed));
      BigDecimal minDue = MinimumDueCalculator.due(minimumDueTerms, inputs);
      BigDecimal payment = minDue.min(totalOwed);

      BigDecimal requiredToCoverCharges = financeCharges.add(gst).add(overlimitFee).add(overlimitFeeGst);
      if (payment.compareTo(requiredToCoverCharges) <= 0) {
        table.add(new CycleRow(cycleNum, Money.paise(openingPrincipal), Money.paise(financeCharges), Money.paise(gst),
            Money.paise(overlimitFee), Money.paise(overLimit), Money.paise(minDue), null, null, true));
        return new MinimumPaymentResult(startingPrincipalPaise, null, true, null, null, null, null, table,
            "WARNING: minimum due does not cover interest. Balance grows indefinitely under minimum-only payments.");
      }

      List<Lot> lots = new ArrayList<>(openingLots);
      lots.add(new Lot(cycleEnd, "finance_charges", Money.round2(financeCharges.add(gst)), retailApr, null));
      if (overlimitFee.add(overlimitFeeGst).compareTo(BigDecimal.ZERO) > 0) {
        lots.add(new Lot(cycleEnd, "fees", Money.round2(overlimitFee.add(overlimitFeeGst)), retailApr, null));
      }
      LedgerEngine.applyPaymentWaterfall(lots, payment, terms.paymentAllocationOrder());
      BigDecimal closingPrincipal = Money.round2(LedgerEngine.sumRemaining(lots));

      table.add(new CycleRow(cycleNum, Money.paise(openingPrincipal), Money.paise(financeCharges), Money.paise(gst),
          Money.paise(overlimitFee), Money.paise(overLimit), Money.paise(minDue), Money.paise(payment),
          Money.paise(closingPrincipal), false));

      openingLots = lots.stream().filter(Lot::open).map(l -> l.copyWithDate(cycleEnd))
          .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
      cycleStartDate = cycleStartDate.plusDays(cycleLengthDays);

      if (closingPrincipal.compareTo(BigDecimal.ONE) < 0) {
        break;
      }
    }

    long totalInterest = sum(table, CycleRow::financeChargesPaise);
    long totalGst = sum(table, CycleRow::gstPaise);
    long totalOverlimitFees = sum(table, CycleRow::overlimitFeePaise);
    long totalExtra = totalInterest + totalGst + totalOverlimitFees;

    String verdict = "Paying only the minimum takes %d billing cycles and costs ₹%s extra in interest + GST%s."
        .formatted(cycleNum, Money.rs(Money.rupees(totalExtra)), totalOverlimitFees > 0 ? " + overlimit fees" : "");

    return new MinimumPaymentResult(startingPrincipalPaise, cycleNum, false, totalInterest, totalGst,
        totalOverlimitFees, totalExtra, table, verdict);
  }

  private static long sum(List<CycleRow> rows, Function<CycleRow, Long> field) {
    long total = 0;
    for (CycleRow row : rows) {
      Long v = field.apply(row);
      if (v != null) {
        total += v;
      }
    }
    return total;
  }
}
