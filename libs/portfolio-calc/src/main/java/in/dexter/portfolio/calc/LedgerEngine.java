package in.dexter.portfolio.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The day-by-day, lot-based average-daily-balance interest engine every other engine here is built on (old repo
 * {@code calc/ledger/LedgerEngine}, itself a port of the Node calculation-engine's
 * {@code ledger.js buildLedgerAndInterest}/{@code applyPaymentWaterfall}).
 *
 * <p>Simple (non-compounding) interest accrues per lot per day. Compounding across cycles happens only because
 * unpaid interest becomes its own lot at cycle end — that is the caller's job (see {@link MinimumPaymentSimulator}),
 * not this class's.
 *
 * <p>Pure and stateless: static methods, no Spring, paise in and out.
 *
 * <p>Not ported, as in the old Java port: the per-day {@code dailyLog} audit array. It is a diagnostic artefact
 * nothing in the simulators or the exit-path search consumes — only the total interest and the closing lots are. A
 * real gap if a day-by-day display is ever wanted; stated rather than silently dropped (BUILD_README §7).
 */
public final class LedgerEngine {

  /** Intermediate precision before the final {@code round2}, as the original had it. */
  private static final int INTEREST_SCALE = 10;

  private static final BigDecimal DAYS_IN_YEAR = BigDecimal.valueOf(365);

  private LedgerEngine() {}

  /** A lot as it crosses the library's boundary: paise and bps. */
  public record LotView(LocalDate date, String bucket, long remainingPaise, int aprBps, String referenceNumber) {}

  /** @param closingLots the lots still open at {@code cycleEnd}, in the order they were opened */
  public record LedgerResult(long totalInterestPaise, List<LotView> closingLots) {}

  /**
   * Accrues a cycle's interest over {@code [cycleStart, cycleEnd]} inclusive.
   *
   * @param graceActive when true, a new retail purchase lot is created at 0 % — a cycle in grace. Cash advances are
   *     unaffected: they never get grace.
   */
  public static LedgerResult ledger(List<LedgerTransaction> transactions, LedgerTerms terms, LocalDate cycleStart,
      LocalDate cycleEnd, List<LotView> openingLots, boolean graceActive) {
    Accrual accrual = accrue(transactions, terms, cycleStart, cycleEnd, toLots(openingLots), graceActive);
    return new LedgerResult(Money.paise(accrual.totalInterest()), views(accrual.closingLots()));
  }

  /** The interest and the lots left open — rupees, for the engines in this package that chain cycles together. */
  record Accrual(BigDecimal totalInterest, List<Lot> closingLots) {}

  static Accrual accrue(List<LedgerTransaction> transactions, LedgerTerms terms, LocalDate cycleStart,
      LocalDate cycleEnd, List<Lot> openingLots, boolean graceActive) {
    if (cycleEnd.isBefore(cycleStart)) {
      throw new IllegalArgumentException("cycleEnd must not be before cycleStart");
    }
    List<Lot> lots = openingLots.stream().map(Lot::copy).collect(Collectors.toCollection(ArrayList::new));
    Map<LocalDate, List<LedgerTransaction>> byDate =
        transactions.stream().collect(Collectors.groupingBy(LedgerTransaction::date));
    BigDecimal retailApr = Money.percent(terms.aprBps());
    BigDecimal cashApr = Money.percent(terms.cashAdvanceAprBps());

    BigDecimal totalInterest = BigDecimal.ZERO;
    for (LocalDate day = cycleStart; !day.isAfter(cycleEnd); day = day.plusDays(1)) {
      for (LedgerTransaction txn : byDate.getOrDefault(day, List.of())) {
        apply(lots, txn, day, terms, retailApr, cashApr, graceActive);
      }
      totalInterest = totalInterest.add(interestForDay(lots, day));
    }
    return new Accrual(Money.round2(totalInterest), lots.stream().filter(Lot::open).toList());
  }

  private static void apply(List<Lot> lots, LedgerTransaction txn, LocalDate day, LedgerTerms terms,
      BigDecimal retailApr, BigDecimal cashApr, boolean graceActive) {
    BigDecimal amount = Money.rupees(txn.amountPaise());
    switch (txn.type()) {
      case PURCHASE -> {
        String bucket = txn.bucket() != null ? txn.bucket() : "retail_purchases";
        BigDecimal apr = "cash_advances".equals(bucket) ? cashApr : (graceActive ? BigDecimal.ZERO : retailApr);
        lots.add(new Lot(day, bucket, amount, apr, txn.referenceNumber()));
      }
      case PAYMENT -> applyPaymentWaterfall(lots, amount, terms.paymentAllocationOrder());
      case REFUND_SPECIFIC -> {
        // Reverses the specific original lot, not the generic waterfall. Falls back to the waterfall when the
        // referenced lot cannot be found (it was in a prior, already-closed cycle).
        Lot target = lots.stream()
            .filter(l -> l.referenceNumber != null && l.referenceNumber.equals(txn.originalReferenceNumber())
                && l.open())
            .findFirst().orElse(null);
        if (target != null) {
          BigDecimal reduceBy = target.remaining.min(amount);
          target.remaining = Money.round2(target.remaining.subtract(reduceBy));
        } else {
          applyPaymentWaterfall(lots, amount, terms.paymentAllocationOrder());
        }
      }
    }
  }

  private static BigDecimal interestForDay(List<Lot> lots, LocalDate day) {
    BigDecimal dayInterest = BigDecimal.ZERO;
    for (Lot lot : lots) {
      if (lot.open() && !lot.date.isAfter(day)) {
        dayInterest = dayInterest.add(lot.remaining
            .multiply(lot.apr.divide(Money.HUNDRED, INTEREST_SCALE, RoundingMode.HALF_UP))
            .divide(DAYS_IN_YEAR, INTEREST_SCALE, RoundingMode.HALF_UP));
      }
    }
    return dayInterest;
  }

  /**
   * Pays lots down bucket by bucket in {@code allocationOrder}, oldest lot first within a bucket.
   *
   * @return the unpaid remainder (zero when the payment covered every open lot)
   */
  static BigDecimal applyPaymentWaterfall(List<Lot> lots, BigDecimal paymentAmount, List<String> allocationOrder) {
    BigDecimal remainingPayment = paymentAmount;
    for (String bucket : allocationOrder) {
      if (remainingPayment.compareTo(BigDecimal.ZERO) <= 0) {
        break;
      }
      List<Lot> bucketLots = lots.stream()
          .filter(l -> bucket.equals(l.bucket) && l.open())
          .sorted(Comparator.comparing(l -> l.date))
          .toList();
      for (Lot lot : bucketLots) {
        if (remainingPayment.compareTo(BigDecimal.ZERO) <= 0) {
          break;
        }
        BigDecimal pay = lot.remaining.min(remainingPayment);
        lot.remaining = Money.round2(lot.remaining.subtract(pay));
        remainingPayment = Money.round2(remainingPayment.subtract(pay));
      }
    }
    return remainingPayment;
  }

  static BigDecimal sumRemaining(List<Lot> lots) {
    return lots.stream().map(l -> l.remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  static List<Lot> toLots(List<LotView> views) {
    return views.stream()
        .map(v -> new Lot(v.date(), v.bucket(), Money.rupees(v.remainingPaise()), Money.percent(v.aprBps()),
            v.referenceNumber()))
        .collect(Collectors.toCollection(ArrayList::new));
  }

  static List<LotView> views(List<Lot> lots) {
    return lots.stream()
        .map(l -> new LotView(l.date, l.bucket, Money.paise(l.remaining), Money.bps(l.apr), l.referenceNumber))
        .toList();
  }
}
