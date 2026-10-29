package in.dexter.portfolio.calc;

import in.dexter.portfolio.calc.MinimumDueCalculator.MinimumDueTerms;
import in.dexter.portfolio.calc.MinimumPaymentSimulator.MinimumPaymentResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Given any current balance, the safest ways out: pay in full now, a flat payment per cycle that clears it in N
 * cycles, or carry on paying only the minimum (old repo {@code calc/ExitPathCalculator}, itself a port of
 * {@code exitPathEngine.js}).
 *
 * <p>The flat payment is found by binary search over the payment amount, running the same {@link LedgerEngine} math
 * on each iteration. Slower than a closed-form formula, and deliberately so, per the original: it is provably
 * consistent with every other number these engines produce.
 *
 * <p>Inherits {@link MinimumPaymentSimulator}'s stated scope: no custom fees in the minimum-only baseline.
 */
public final class ExitPathCalculator {

  /** The anchor the original used; only the day counts matter. */
  static final LocalDate SIMULATION_EPOCH = LocalDate.of(2026, 1, 1);

  /** The binary search's ceiling multiple of the balance — generous enough for a very high APR. */
  private static final BigDecimal HI_MULTIPLE = new BigDecimal("1.5");

  /** Stop once the bracket is inside a rupee, or after this many halvings. */
  private static final int MAX_ITERATIONS = 25;

  private static final int CALC_SCALE = 10;

  /** The minimum-only baseline runs to 30 years, as the original did. */
  private static final int MINIMUM_ONLY_MAX_CYCLES = 360;

  private ExitPathCalculator() {}

  /** @param clearedByCycle the cycle the balance cleared in; null when it did not clear in time */
  public record FlatPaymentResult(Integer clearedByCycle, long totalInterestPaise, long totalGstPaise,
      boolean feasible) {}

  public record FoundFlatPayment(long paymentPaise, Integer clearedByCycle, long totalInterestPaise,
      long totalGstPaise) {}

  public record PayInFullPath(long amountRequiredPaise, long totalCostPaise, String verdict) {}

  /** Everything but {@code targetCycles}, {@code feasible} and {@code verdict} is null when not reachable. */
  public record TargetedPayoffPath(int targetCycles, boolean feasible, Long requiredPaymentPerCyclePaise,
      Integer cyclesToDebtFree, Long totalInterestAndGstPaise, Long totalCostPaise, String verdict) {}

  public record ContinueMinimumPath(boolean feasible, Integer cyclesToDebtFree, Long totalCostPaise,
      Long totalExtraPaidPaise, String verdict) {}

  public record SafeExitPathsResult(boolean feasible, long balancePaise, String message, PayInFullPath payInFullNow,
      List<TargetedPayoffPath> targetedPaths, ContinueMinimumPath continueMinimum, String recommendedPath,
      String summary) {}

  /** Runs a fixed payment per cycle against a starting balance for up to {@code targetCycles} cycles. */
  public static FlatPaymentResult simulateFlatPayment(LedgerTerms terms, long startingPrincipalPaise,
      long flatPaymentPaise, int targetCycles, int cycleLengthDays) {
    Flat flat = flat(terms, Money.rupees(startingPrincipalPaise), Money.rupees(flatPaymentPaise), targetCycles,
        cycleLengthDays);
    return new FlatPaymentResult(flat.clearedByCycle(), Money.paise(flat.totalInterest()),
        Money.paise(flat.totalGst()), flat.feasible());
  }

  private record Flat(Integer clearedByCycle, BigDecimal totalInterest, BigDecimal totalGst, boolean feasible) {}

  private static Flat flat(LedgerTerms terms, BigDecimal startingPrincipal, BigDecimal flatPayment, int targetCycles,
      int cycleLengthDays) {
    if (targetCycles < 1 || cycleLengthDays < 1) {
      throw new IllegalArgumentException("targetCycles and cycleLengthDays must be at least 1");
    }
    BigDecimal retailApr = Money.percent(terms.aprBps());
    List<Lot> openingLots = new ArrayList<>(List.of(
        new Lot(SIMULATION_EPOCH, "retail_purchases", startingPrincipal, retailApr, null)));
    LocalDate cycleStartDate = SIMULATION_EPOCH;
    BigDecimal totalInterest = BigDecimal.ZERO;
    BigDecimal totalGst = BigDecimal.ZERO;

    for (int cycle = 1; cycle <= targetCycles; cycle++) {
      BigDecimal openingPrincipal = Money.round2(LedgerEngine.sumRemaining(openingLots));
      if (openingPrincipal.compareTo(BigDecimal.ZERO) <= 0) {
        return new Flat(cycle - 1, totalInterest, totalGst, true);
      }

      LocalDate cycleStart = cycleStartDate;
      LocalDate cycleEnd = cycleStartDate.plusDays(cycleLengthDays - 1L);

      BigDecimal financeCharges =
          LedgerEngine.accrue(List.of(), terms, cycleStart, cycleEnd, openingLots, false).totalInterest();
      BigDecimal gst = Money.gst(financeCharges);
      totalInterest = Money.round2(totalInterest.add(financeCharges));
      totalGst = Money.round2(totalGst.add(gst));

      BigDecimal owed = Money.round2(openingPrincipal.add(financeCharges).add(gst));
      BigDecimal payment = flatPayment.min(owed);

      List<Lot> lots = new ArrayList<>(openingLots);
      lots.add(new Lot(cycleEnd, "finance_charges", Money.round2(financeCharges.add(gst)), retailApr, null));
      LedgerEngine.applyPaymentWaterfall(lots, payment, terms.paymentAllocationOrder());
      openingLots = lots.stream().filter(Lot::open).map(l -> l.copyWithDate(cycleEnd))
          .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
      cycleStartDate = cycleStartDate.plusDays(cycleLengthDays);

      if (LedgerEngine.sumRemaining(openingLots).compareTo(BigDecimal.ONE) < 0) {
        return new Flat(cycle, totalInterest, totalGst, true);
      }
    }
    return new Flat(null, totalInterest, totalGst, false);
  }

  /**
   * The smallest flat payment per cycle that clears {@code balance} within {@code targetCycles}.
   *
   * @return null when even 1.5× the balance per cycle cannot do it
   */
  public static FoundFlatPayment findMinimumFlatPaymentForCycles(LedgerTerms terms, long balancePaise,
      int targetCycles, int cycleLengthDays) {
    Found found = search(terms, Money.rupees(balancePaise), targetCycles, cycleLengthDays);
    return found == null ? null
        : new FoundFlatPayment(Money.paise(found.payment()), found.clearedByCycle(),
            Money.paise(found.totalInterest()), Money.paise(found.totalGst()));
  }

  private record Found(BigDecimal payment, Integer clearedByCycle, BigDecimal totalInterest, BigDecimal totalGst) {}

  private static Found search(LedgerTerms terms, BigDecimal balance, int targetCycles, int cycleLengthDays) {
    // The absolute floor: even at zero interest the balance needs this much per cycle.
    BigDecimal lo = Money.round2(balance.divide(BigDecimal.valueOf(targetCycles), CALC_SCALE, RoundingMode.HALF_UP));
    BigDecimal hi = Money.round2(balance.multiply(HI_MULTIPLE));
    Found best = null;

    for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
      BigDecimal mid = Money.round2(lo.add(hi).divide(BigDecimal.valueOf(2), CALC_SCALE, RoundingMode.HALF_UP));
      Flat result = flat(terms, balance, mid, targetCycles, cycleLengthDays);
      if (result.feasible()) {
        best = new Found(mid, result.clearedByCycle(), result.totalInterest(), result.totalGst());
        hi = mid;
      } else {
        lo = mid;
      }
      if (hi.subtract(lo).compareTo(BigDecimal.ONE) < 0) {
        break;
      }
    }
    return best;
  }

  /** The main entry point: the exit paths open to a balance right now. */
  public static SafeExitPathsResult computeSafeExitPaths(LedgerTerms terms, MinimumDueTerms minimumDueTerms,
      long currentBalancePaise, int cycleLengthDays, List<Integer> targetCyclesOptions) {
    if (currentBalancePaise <= 0) {
      return new SafeExitPathsResult(true, 0L, "Balance is already zero — nothing to exit from.", null, List.of(),
          null, null, null);
    }
    BigDecimal balance = Money.rupees(currentBalancePaise);

    PayInFullPath payInFullNow = new PayInFullPath(currentBalancePaise, currentBalancePaise,
        "Pay ₹%s now and this is over — grace restored, zero further interest.".formatted(Money.rs(balance)));

    List<TargetedPayoffPath> targetedPaths = targetCyclesOptions.stream().map(n -> {
      Found found = search(terms, balance, n, cycleLengthDays);
      if (found == null) {
        return new TargetedPayoffPath(n, false, null, null, null, null,
            "Clearing this in %d cycles isn't reachable at a sane payment level — try more cycles.".formatted(n));
      }
      BigDecimal interestAndGst = Money.round2(found.totalInterest().add(found.totalGst()));
      return new TargetedPayoffPath(n, true, Money.paise(found.payment()), found.clearedByCycle(),
          Money.paise(interestAndGst), Money.paise(Money.round2(balance.add(interestAndGst))),
          "Pay ₹%s/cycle and be debt-free in %d cycle(s) — costs ₹%s in interest total."
              .formatted(Money.rs(found.payment()), found.clearedByCycle(), Money.rs(interestAndGst)));
    }).toList();

    MinimumPaymentResult minimumOnly = MinimumPaymentSimulator.simulate(terms, minimumDueTerms, currentBalancePaise,
        cycleLengthDays, MINIMUM_ONLY_MAX_CYCLES, null, null);
    ContinueMinimumPath continueMinimum = minimumOnly.infiniteDebtWarning()
        ? new ContinueMinimumPath(false, null, null, null,
            "WARNING: at minimum-only payments, this balance never clears — it grows forever.")
        : new ContinueMinimumPath(true, minimumOnly.cyclesToPayoff(),
            currentBalancePaise + minimumOnly.totalExtraPaidPaise(), minimumOnly.totalExtraPaidPaise(),
            "Minimum-only takes %d cycles and costs ₹%s extra — by far the most expensive realistic path."
                .formatted(minimumOnly.cyclesToPayoff(),
                    Money.rs(Money.rupees(minimumOnly.totalExtraPaidPaise()))));

    TargetedPayoffPath cheapest = targetedPaths.stream()
        .filter(TargetedPayoffPath::feasible)
        .min(Comparator.comparingLong(TargetedPayoffPath::totalInterestAndGstPaise))
        .orElse(null);
    String recommended = cheapest != null ? "targeted_payoff_%dcycles".formatted(cheapest.targetCycles())
        : "pay_in_full_now";

    String extra = continueMinimum.totalExtraPaidPaise() != null
        ? "₹" + Money.rs(Money.rupees(continueMinimum.totalExtraPaidPaise()))
        : "infinite";
    String summary = ("From ₹%s owed: pay in full for zero cost, or pick a targeted payoff — every extra cycle you "
        + "stretch this out costs real money, and minimum-only is the worst option by a wide margin (%s extra).")
        .formatted(Money.rs(balance), extra);

    return new SafeExitPathsResult(true, currentBalancePaise, null, payInFullNow, targetedPaths, continueMinimum,
        recommended, summary);
  }
}
