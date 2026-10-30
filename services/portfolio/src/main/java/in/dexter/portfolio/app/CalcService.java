package in.dexter.portfolio.app;

import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.portfolio.calc.CreditUtilizationCalculator;
import in.dexter.portfolio.calc.CreditUtilizationCalculator.OverlimitFeeTerms;
import in.dexter.portfolio.calc.CreditUtilizationCalculator.UtilizationResult;
import in.dexter.portfolio.calc.CustomFeeCalculator;
import in.dexter.portfolio.calc.CustomFeeCalculator.AmountType;
import in.dexter.portfolio.calc.CustomFeeCalculator.AppliesTo;
import in.dexter.portfolio.calc.CustomFeeCalculator.CustomFee;
import in.dexter.portfolio.calc.CustomFeeCalculator.CustomFeesResult;
import in.dexter.portfolio.calc.ExitPathCalculator;
import in.dexter.portfolio.calc.ExitPathCalculator.SafeExitPathsResult;
import in.dexter.portfolio.calc.FullPaymentCalculator;
import in.dexter.portfolio.calc.FullPaymentCalculator.FullPaymentResult;
import in.dexter.portfolio.calc.Gst;
import in.dexter.portfolio.calc.LateFeeAndDelinquencyCalculator;
import in.dexter.portfolio.calc.LateFeeAndDelinquencyCalculator.DelinquencyClassification;
import in.dexter.portfolio.calc.LateFeeAndDelinquencyCalculator.LateFeeTier;
import in.dexter.portfolio.calc.LedgerEngine;
import in.dexter.portfolio.calc.LedgerEngine.LedgerResult;
import in.dexter.portfolio.calc.LedgerEngine.LotView;
import in.dexter.portfolio.calc.LedgerTerms;
import in.dexter.portfolio.calc.LedgerTransaction;
import in.dexter.portfolio.calc.MinimumDueCalculator;
import in.dexter.portfolio.calc.MinimumDueCalculator.FormulaType;
import in.dexter.portfolio.calc.MinimumDueCalculator.MinimumDueInputs;
import in.dexter.portfolio.calc.MinimumDueCalculator.MinimumDueTerms;
import in.dexter.portfolio.calc.MinimumPaymentSimulator;
import in.dexter.portfolio.calc.MinimumPaymentSimulator.MinimumPaymentResult;
import in.dexter.portfolio.calc.NoPaymentSimulator;
import in.dexter.portfolio.calc.NoPaymentSimulator.NoPaymentResult;
import in.dexter.portfolio.calc.PartialPaymentCalculator;
import in.dexter.portfolio.calc.PartialPaymentCalculator.PartialPaymentResult;
import in.dexter.portfolio.calc.RepaymentPathCalculator;
import in.dexter.portfolio.calc.RepaymentPathCalculator.BestPathResult;
import in.dexter.portfolio.calc.RepaymentPathCalculator.CardBalance;
import in.dexter.portfolio.calc.TransactionType;
import in.dexter.portfolio.calc.EmiMath;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /v1/calc (MASTER_PLAN P12 step 2): the calc library behind stateless endpoints. No database, no tenant data, no
 * events — every request carries its own terms and every answer is a pure function of the body (A-73).
 *
 * <p>This layer only validates and maps. The arithmetic is {@code libs/portfolio-calc}, ported from the old repo
 * with its tests as golden fixtures (ADR-0018); EMI is P11's {@link EmiMath}, which is v2's EMI engine and what
 * {@code emi_plans} already uses.
 *
 * <p>Caps are deliberate: these endpoints are reachable by any signed-in caller at 60 requests a minute, and the
 * engines are loops over days, cycles and lots, so every list length and cycle count is bounded here.
 */
public final class CalcService {
  public static final String PATH = "/v1/calc";

  /** Caps on the work one request can ask for. */
  static final int MAX_TRANSACTIONS = 500;
  static final int MAX_LOTS = 200;
  static final int MAX_TIERS = 24;
  static final int MAX_FEES = 24;
  static final int MAX_CARDS = 25;
  static final int MAX_TARGET_OPTIONS = 6;
  static final int MAX_CYCLES = 360;
  static final int MAX_CYCLE_DAYS = 90;
  static final int MAX_CYCLE_WINDOW_DAYS = 400;
  static final int MAX_BPS = 10_000;
  static final long MAX_AMOUNT = Inputs.MAX_AMOUNT;

  private final Clock clock;

  public CalcService(Clock clock) {
    this.clock = clock;
  }

  // ---- requests

  public record TermsReq(Integer aprBps, Integer cashAdvanceAprBps, List<String> paymentAllocationOrder) {}

  public record LotReq(String date, String bucket, Long remainingPaise, Integer aprBps, String referenceNumber) {}

  public record TxnReq(String date, String type, Long amountPaise, String bucket, String referenceNumber,
      String originalReferenceNumber) {}

  public record TierReq(Long minOutstandingPaise, Long maxOutstandingPaise, Long lateFeePaise) {}

  public record OverlimitFeeReq(Integer percentBps, Long flatMinimumPaise) {}

  public record FeeReq(String id, String name, String appliesTo, String amountType, Long flatPaise,
      Integer percentBps) {}

  public record MinimumDueTermsReq(String formulaType, Long flatAmountPaise, Integer percentBps, Long flatFloorPaise,
      Integer retailCashPercentBps, Long roundUpToNearestPaise) {}

  public record CardReq(String id, String name, Long balancePaise, Integer aprBps, Long minimumDuePaise) {}

  public record EmiReq(Long principalPaise, Integer annualRateBps, Integer tenureMonths, Integer gstBps,
      Long processingFeePaise, String firstDueDate) {}

  public record LedgerReq(TermsReq terms, List<TxnReq> transactions, String cycleStart, String cycleEnd,
      List<LotReq> openingLots, Boolean graceActive) {}

  public record MinimumDueReq(MinimumDueTermsReq terms, Long retailCashOutstandingPaise, Long financeChargesPaise,
      Long emiAmountPaise, Long feesAndChargesPaise, Long gstAmountPaise, Long overlimitAmountPaise,
      Long totalAmountDuePaise) {}

  public record LateFeeReq(Long outstandingBalancePaise, List<TierReq> tiers, Integer daysOverdue) {}

  public record UtilizationReq(Long spentPaise, Long creditLimitPaise, OverlimitFeeReq overlimitFee) {}

  public record CustomFeesReq(List<FeeReq> fees, Long balancePaise, Integer cycleNumber) {}

  public record FullPaymentReq(Long statementBalancePaise, List<FeeReq> customFees) {}

  public record PartialPaymentReq(TermsReq terms, List<TxnReq> transactions, String cycleStart, String cycleEnd,
      Long paymentPaise, Boolean graceActiveThisCycle, List<TxnReq> nextCycleTransactions, String nextCycleStart,
      String nextCycleEnd) {}

  public record NoPaymentReq(TermsReq terms, List<TierReq> lateFeeTiers, OverlimitFeeReq overlimitFee,
      Long statementBalancePaise, Long creditLimitPaise, Integer cycleLengthDays) {}

  public record MinimumPaymentReq(TermsReq terms, MinimumDueTermsReq minimumDueTerms, Long startingPrincipalPaise,
      Integer cycleLengthDays, Integer maxCycles, Long creditLimitPaise, OverlimitFeeReq overlimitFee) {}

  public record RepaymentPathReq(List<CardReq> cards, Long monthlyBudgetPaise) {}

  public record ExitPathsReq(TermsReq terms, MinimumDueTermsReq minimumDueTerms, Long currentBalancePaise,
      Integer cycleLengthDays, List<Integer> targetCycles) {}

  // ---- responses that combine two engines

  public record MinimumDueResult(long minimumDuePaise) {}

  public record LateFeeResult(long lateFeePaise, long gstOnLateFeePaise, DelinquencyClassification delinquency) {}

  public record UtilizationAndFeeResult(UtilizationResult utilization, long overlimitFeePaise) {}

  // ---- use cases

  public EmiMath.Schedule emi(EmiReq body) {
    List<FieldError> e = errors(body);
    long principal = amount("principalPaise", body.principalPaise(), true, e);
    int rate = bps("annualRateBps", body.annualRateBps(), 6000, true, e);
    int tenure = count("tenureMonths", body.tenureMonths(), 1, 84, true, e);
    int gst = body.gstBps() == null ? 1800 : bps("gstBps", body.gstBps(), 2800, false, e);
    long fee = amount("processingFeePaise", body.processingFeePaise(), false, e);
    LocalDate firstDue = Inputs.date("firstDueDate", body.firstDueDate(), e);
    if (fee > principal) {
      e.add(new FieldError("processingFeePaise", "OUT_OF_RANGE", "0 to the principal"));
    }
    done(e);
    // A quote with no first instalment date starts a month out, so the schedule still carries real due dates.
    LocalDate due = firstDue == null ? LocalDate.now(clock).plusMonths(1) : firstDue;
    return guard(() -> EmiMath.schedule(principal, rate, tenure, gst, fee, due));
  }

  public LedgerResult ledger(LedgerReq body) {
    List<FieldError> e = errors(body);
    LedgerTerms terms = terms(body.terms(), e);
    List<LedgerTransaction> txns = transactions("transactions", body.transactions(), e);
    LocalDate start = requiredDate("cycleStart", body.cycleStart(), e);
    LocalDate end = requiredDate("cycleEnd", body.cycleEnd(), e);
    window("cycleEnd", start, end, e);
    List<LotView> lots = lots(body.openingLots(), e);
    done(e);
    return guard(() -> LedgerEngine.ledger(txns, terms, start, end, lots, Boolean.TRUE.equals(body.graceActive())));
  }

  public MinimumDueResult minimumDue(MinimumDueReq body) {
    List<FieldError> e = errors(body);
    MinimumDueTerms terms = minimumDueTerms("terms", body.terms(), e);
    MinimumDueInputs inputs = new MinimumDueInputs(
        amount("retailCashOutstandingPaise", body.retailCashOutstandingPaise(), false, e),
        amount("financeChargesPaise", body.financeChargesPaise(), false, e),
        amount("emiAmountPaise", body.emiAmountPaise(), false, e),
        amount("feesAndChargesPaise", body.feesAndChargesPaise(), false, e),
        amount("gstAmountPaise", body.gstAmountPaise(), false, e),
        amount("overlimitAmountPaise", body.overlimitAmountPaise(), false, e),
        amount("totalAmountDuePaise", body.totalAmountDuePaise(), false, e));
    done(e);
    return guard(() -> new MinimumDueResult(MinimumDueCalculator.calculate(terms, inputs)));
  }

  public LateFeeResult lateFee(LateFeeReq body) {
    List<FieldError> e = errors(body);
    long outstanding = amount("outstandingBalancePaise", body.outstandingBalancePaise(), true, e);
    List<LateFeeTier> tiers = tiers("tiers", body.tiers(), true, e);
    int daysOverdue = count("daysOverdue", body.daysOverdue(), 0, 3650, true, e);
    done(e);
    return guard(() -> {
      long fee = LateFeeAndDelinquencyCalculator.lateFee(outstanding, tiers);
      return new LateFeeResult(fee, Gst.on(fee),
          LateFeeAndDelinquencyCalculator.classifyDelinquencyStage(daysOverdue));
    });
  }

  public UtilizationAndFeeResult utilization(UtilizationReq body) {
    List<FieldError> e = errors(body);
    long spent = amount("spentPaise", body.spentPaise(), true, e);
    Long limit = optionalAmount("creditLimitPaise", body.creditLimitPaise(), e);
    if (limit != null && limit <= 0) {
      e.add(new FieldError("creditLimitPaise", "OUT_OF_RANGE", "A configured limit is positive"));
    }
    OverlimitFeeTerms fee = overlimitFee("overlimitFee", body.overlimitFee(), e);
    done(e);
    return guard(() -> {
      UtilizationResult u = CreditUtilizationCalculator.utilization(spent, limit);
      long charged = fee == null ? 0L : CreditUtilizationCalculator.overlimitFee(u.overLimitPaise(), fee);
      return new UtilizationAndFeeResult(u, charged);
    });
  }

  public CustomFeesResult customFees(CustomFeesReq body) {
    List<FieldError> e = errors(body);
    List<CustomFee> fees = fees("fees", body.fees(), e);
    long balance = amount("balancePaise", body.balancePaise(), true, e);
    int cycle = count("cycleNumber", body.cycleNumber(), 1, MAX_CYCLES, true, e);
    done(e);
    return guard(() -> CustomFeeCalculator.computeForCycle(fees, balance, cycle));
  }

  public FullPaymentResult fullPayment(FullPaymentReq body) {
    List<FieldError> e = errors(body);
    long balance = amount("statementBalancePaise", body.statementBalancePaise(), true, e);
    List<CustomFee> fees = fees("customFees", body.customFees(), e);
    done(e);
    return guard(() -> FullPaymentCalculator.simulate(balance, fees));
  }

  public PartialPaymentResult partialPayment(PartialPaymentReq body) {
    List<FieldError> e = errors(body);
    LedgerTerms terms = terms(body.terms(), e);
    List<LedgerTransaction> txns = transactions("transactions", body.transactions(), e);
    LocalDate start = requiredDate("cycleStart", body.cycleStart(), e);
    LocalDate end = requiredDate("cycleEnd", body.cycleEnd(), e);
    window("cycleEnd", start, end, e);
    long payment = amount("paymentPaise", body.paymentPaise(), true, e);
    List<LedgerTransaction> nextTxns = transactions("nextCycleTransactions", body.nextCycleTransactions(), e);
    LocalDate nextStart = Inputs.date("nextCycleStart", body.nextCycleStart(), e);
    LocalDate nextEnd = Inputs.date("nextCycleEnd", body.nextCycleEnd(), e);
    if ((nextStart == null) != (nextEnd == null)) {
      e.add(new FieldError("nextCycleEnd", "REQUIRED", "Both next-cycle dates, or neither"));
    }
    window("nextCycleEnd", nextStart, nextEnd, e);
    done(e);
    return guard(() -> PartialPaymentCalculator.simulate(terms, txns, start, end, payment,
        Boolean.TRUE.equals(body.graceActiveThisCycle()), nextTxns, nextStart, nextEnd));
  }

  public NoPaymentResult noPayment(NoPaymentReq body) {
    List<FieldError> e = errors(body);
    LedgerTerms terms = terms(body.terms(), e);
    List<LateFeeTier> tiers = tiers("lateFeeTiers", body.lateFeeTiers(), true, e);
    OverlimitFeeTerms fee = overlimitFee("overlimitFee", body.overlimitFee(), e);
    long balance = amount("statementBalancePaise", body.statementBalancePaise(), true, e);
    Long limit = optionalAmount("creditLimitPaise", body.creditLimitPaise(), e);
    if (limit != null && limit <= 0) {
      e.add(new FieldError("creditLimitPaise", "OUT_OF_RANGE", "A configured limit is positive"));
    }
    int cycleDays = count("cycleLengthDays", body.cycleLengthDays(), 1, MAX_CYCLE_DAYS, true, e);
    done(e);
    return guard(() -> NoPaymentSimulator.simulate(terms, tiers, fee, balance, limit, cycleDays));
  }

  public MinimumPaymentResult minimumPayment(MinimumPaymentReq body) {
    List<FieldError> e = errors(body);
    LedgerTerms terms = terms(body.terms(), e);
    MinimumDueTerms minimumDue = minimumDueTerms("minimumDueTerms", body.minimumDueTerms(), e);
    long principal = amount("startingPrincipalPaise", body.startingPrincipalPaise(), true, e);
    int cycleDays = count("cycleLengthDays", body.cycleLengthDays(), 1, MAX_CYCLE_DAYS, true, e);
    int maxCycles = body.maxCycles() == null ? MAX_CYCLES
        : count("maxCycles", body.maxCycles(), 1, MAX_CYCLES, false, e);
    Long limit = optionalAmount("creditLimitPaise", body.creditLimitPaise(), e);
    if (limit != null && limit <= 0) {
      e.add(new FieldError("creditLimitPaise", "OUT_OF_RANGE", "A configured limit is positive"));
    }
    OverlimitFeeTerms fee = overlimitFee("overlimitFee", body.overlimitFee(), e);
    done(e);
    return guard(() -> MinimumPaymentSimulator.simulate(terms, minimumDue, principal, cycleDays, maxCycles, limit,
        fee));
  }

  public BestPathResult repaymentPath(RepaymentPathReq body) {
    List<FieldError> e = errors(body);
    List<CardBalance> cards = new ArrayList<>();
    if (body.cards() == null || body.cards().isEmpty()) {
      e.add(new FieldError("cards", "REQUIRED", "At least one card"));
    } else if (body.cards().size() > MAX_CARDS) {
      e.add(new FieldError("cards", "TOO_MANY", "At most " + MAX_CARDS + " cards"));
    } else {
      for (int i = 0; i < body.cards().size(); i++) {
        CardReq c = body.cards().get(i);
        String at = "cards[" + i + "]";
        if (c == null) {
          e.add(new FieldError(at, "REQUIRED", "A card"));
          continue;
        }
        cards.add(new CardBalance(Inputs.text(at + ".id", c.id(), 64, false, e),
            Inputs.text(at + ".name", c.name(), 80, true, e),
            amount(at + ".balancePaise", c.balancePaise(), true, e),
            bps(at + ".aprBps", c.aprBps(), MAX_BPS, true, e),
            amount(at + ".minimumDuePaise", c.minimumDuePaise(), true, e)));
      }
    }
    long budget = amount("monthlyBudgetPaise", body.monthlyBudgetPaise(), true, e);
    done(e);
    return guard(() -> RepaymentPathCalculator.findBestRepaymentPath(cards, budget));
  }

  public SafeExitPathsResult exitPaths(ExitPathsReq body) {
    List<FieldError> e = errors(body);
    LedgerTerms terms = terms(body.terms(), e);
    MinimumDueTerms minimumDue = minimumDueTerms("minimumDueTerms", body.minimumDueTerms(), e);
    long balance = amount("currentBalancePaise", body.currentBalancePaise(), false, e);
    int cycleDays = count("cycleLengthDays", body.cycleLengthDays(), 1, MAX_CYCLE_DAYS, true, e);
    List<Integer> targets = new ArrayList<>();
    if (body.targetCycles() == null || body.targetCycles().isEmpty()) {
      e.add(new FieldError("targetCycles", "REQUIRED", "At least one target cycle count"));
    } else if (body.targetCycles().size() > MAX_TARGET_OPTIONS) {
      e.add(new FieldError("targetCycles", "TOO_MANY", "At most " + MAX_TARGET_OPTIONS + " options"));
    } else {
      for (int i = 0; i < body.targetCycles().size(); i++) {
        targets.add(count("targetCycles[" + i + "]", body.targetCycles().get(i), 1, MAX_CYCLES, true, e));
      }
    }
    done(e);
    return guard(() -> ExitPathCalculator.computeSafeExitPaths(terms, minimumDue, balance, cycleDays, targets));
  }

  // ---- mapping and validation

  private static List<FieldError> errors(Object body) {
    if (body == null) {
      throw new ValidationException(List.of(new FieldError("body", "REQUIRED", "A request body")));
    }
    return new ArrayList<>();
  }

  private static void done(List<FieldError> e) {
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
  }

  /**
   * A library precondition can only be reached through a gap in the checks above, and it is still the caller's
   * input — never a 500.
   */
  private static <T> T guard(java.util.function.Supplier<T> call) {
    try {
      return call.get();
    } catch (IllegalArgumentException ex) {
      throw new ValidationException(List.of(new FieldError("body", "INVALID", ex.getMessage())));
    } catch (ArithmeticException ex) {
      throw new ValidationException(List.of(new FieldError("body", "OUT_OF_RANGE", "The numbers do not fit")));
    }
  }

  private static long amount(String field, Long v, boolean required, List<FieldError> e) {
    if (v == null) {
      if (required) {
        e.add(new FieldError(field, "REQUIRED", "Paise"));
      }
      return 0L;
    }
    if (v < 0 || v > MAX_AMOUNT) {
      e.add(new FieldError(field, "OUT_OF_RANGE", "0 to " + MAX_AMOUNT + " paise"));
      return 0L;
    }
    return v;
  }

  private static Long optionalAmount(String field, Long v, List<FieldError> e) {
    if (v == null) {
      return null;
    }
    if (v < 0 || v > MAX_AMOUNT) {
      e.add(new FieldError(field, "OUT_OF_RANGE", "0 to " + MAX_AMOUNT + " paise"));
      return null;
    }
    return v;
  }

  private static int bps(String field, Integer v, int max, boolean required, List<FieldError> e) {
    if (v == null) {
      if (required) {
        e.add(new FieldError(field, "REQUIRED", "Basis points"));
      }
      return 0;
    }
    if (v < 0 || v > max) {
      e.add(new FieldError(field, "OUT_OF_RANGE", "0-" + max + " bps"));
      return 0;
    }
    return v;
  }

  private static int count(String field, Integer v, int min, int max, boolean required, List<FieldError> e) {
    if (v == null) {
      if (required) {
        e.add(new FieldError(field, "REQUIRED", min + "-" + max));
      }
      return min;
    }
    if (v < min || v > max) {
      e.add(new FieldError(field, "OUT_OF_RANGE", min + "-" + max));
      return min;
    }
    return v;
  }

  private static LocalDate requiredDate(String field, String v, List<FieldError> e) {
    LocalDate d = Inputs.date(field, v, e);
    if (d == null && (v == null || v.isBlank())) {
      e.add(new FieldError(field, "REQUIRED", "A date (YYYY-MM-DD)"));
    }
    return d;
  }

  /** A cycle is walked day by day, so its window is bounded. */
  private static void window(String field, LocalDate start, LocalDate end, List<FieldError> e) {
    if (start == null || end == null) {
      return;
    }
    if (end.isBefore(start)) {
      e.add(new FieldError(field, "OUT_OF_RANGE", "On or after the start"));
    } else if (ChronoUnit.DAYS.between(start, end) >= MAX_CYCLE_WINDOW_DAYS) {
      e.add(new FieldError(field, "OUT_OF_RANGE", "At most " + MAX_CYCLE_WINDOW_DAYS + " days"));
    }
  }

  private static LedgerTerms terms(TermsReq req, List<FieldError> e) {
    if (req == null) {
      e.add(new FieldError("terms", "REQUIRED", "Ledger terms"));
      return null;
    }
    int apr = bps("terms.aprBps", req.aprBps(), MAX_BPS, true, e);
    int cashApr = req.cashAdvanceAprBps() == null ? apr
        : bps("terms.cashAdvanceAprBps", req.cashAdvanceAprBps(), MAX_BPS, false, e);
    List<String> order = req.paymentAllocationOrder();
    if (order == null || order.isEmpty()) {
      e.add(new FieldError("terms.paymentAllocationOrder", "REQUIRED", "At least one bucket"));
      return null;
    }
    if (order.size() > 8) {
      e.add(new FieldError("terms.paymentAllocationOrder", "TOO_MANY", "At most 8 buckets"));
      return null;
    }
    List<String> buckets = new ArrayList<>(order.size());
    for (int i = 0; i < order.size(); i++) {
      String b = Inputs.text("terms.paymentAllocationOrder[" + i + "]", order.get(i), 40, true, e);
      if (b != null) {
        buckets.add(b);
      }
    }
    return buckets.isEmpty() ? null : new LedgerTerms(apr, cashApr, buckets);
  }

  private static List<LedgerTransaction> transactions(String field, List<TxnReq> reqs, List<FieldError> e) {
    if (reqs == null || reqs.isEmpty()) {
      return List.of();
    }
    if (reqs.size() > MAX_TRANSACTIONS) {
      e.add(new FieldError(field, "TOO_MANY", "At most " + MAX_TRANSACTIONS + " transactions"));
      return List.of();
    }
    List<LedgerTransaction> out = new ArrayList<>(reqs.size());
    for (int i = 0; i < reqs.size(); i++) {
      TxnReq t = reqs.get(i);
      String at = field + "[" + i + "]";
      if (t == null) {
        e.add(new FieldError(at, "REQUIRED", "A transaction"));
        continue;
      }
      LocalDate date = requiredDate(at + ".date", t.date(), e);
      TransactionType type = enumValue(at + ".type", t.type(), TransactionType.class, e);
      long amount = amount(at + ".amountPaise", t.amountPaise(), true, e);
      String bucket = Inputs.text(at + ".bucket", t.bucket(), 40, false, e);
      String ref = Inputs.text(at + ".referenceNumber", t.referenceNumber(), 64, false, e);
      String orig = Inputs.text(at + ".originalReferenceNumber", t.originalReferenceNumber(), 64, false, e);
      if (type == TransactionType.REFUND_SPECIFIC && orig == null) {
        e.add(new FieldError(at + ".originalReferenceNumber", "REQUIRED", "The reference being refunded"));
      }
      if (date != null && type != null) {
        out.add(new LedgerTransaction(date, type, amount, bucket, ref, orig));
      }
    }
    return out;
  }

  private static List<LotView> lots(List<LotReq> reqs, List<FieldError> e) {
    if (reqs == null || reqs.isEmpty()) {
      return List.of();
    }
    if (reqs.size() > MAX_LOTS) {
      e.add(new FieldError("openingLots", "TOO_MANY", "At most " + MAX_LOTS + " lots"));
      return List.of();
    }
    List<LotView> out = new ArrayList<>(reqs.size());
    for (int i = 0; i < reqs.size(); i++) {
      LotReq l = reqs.get(i);
      String at = "openingLots[" + i + "]";
      if (l == null) {
        e.add(new FieldError(at, "REQUIRED", "A lot"));
        continue;
      }
      LocalDate date = requiredDate(at + ".date", l.date(), e);
      String bucket = Inputs.text(at + ".bucket", l.bucket(), 40, true, e);
      long remaining = amount(at + ".remainingPaise", l.remainingPaise(), true, e);
      int apr = bps(at + ".aprBps", l.aprBps(), MAX_BPS, true, e);
      String ref = Inputs.text(at + ".referenceNumber", l.referenceNumber(), 64, false, e);
      if (date != null && bucket != null) {
        out.add(new LotView(date, bucket, remaining, apr, ref));
      }
    }
    return out;
  }

  private static List<LateFeeTier> tiers(String field, List<TierReq> reqs, boolean required, List<FieldError> e) {
    if (reqs == null || reqs.isEmpty()) {
      if (required) {
        e.add(new FieldError(field, "REQUIRED", "At least one tier"));
      }
      return List.of();
    }
    if (reqs.size() > MAX_TIERS) {
      e.add(new FieldError(field, "TOO_MANY", "At most " + MAX_TIERS + " tiers"));
      return List.of();
    }
    List<LateFeeTier> out = new ArrayList<>(reqs.size());
    for (int i = 0; i < reqs.size(); i++) {
      TierReq t = reqs.get(i);
      String at = field + "[" + i + "]";
      if (t == null) {
        e.add(new FieldError(at, "REQUIRED", "A tier"));
        continue;
      }
      long min = amount(at + ".minOutstandingPaise", t.minOutstandingPaise(), true, e);
      Long max = optionalAmount(at + ".maxOutstandingPaise", t.maxOutstandingPaise(), e);
      long fee = amount(at + ".lateFeePaise", t.lateFeePaise(), true, e);
      if (max != null && max < min) {
        e.add(new FieldError(at + ".maxOutstandingPaise", "OUT_OF_RANGE", "At or above the minimum"));
      }
      out.add(new LateFeeTier(min, max, fee));
    }
    return out;
  }

  private static OverlimitFeeTerms overlimitFee(String field, OverlimitFeeReq req, List<FieldError> e) {
    if (req == null) {
      return null;
    }
    return new OverlimitFeeTerms(bps(field + ".percentBps", req.percentBps(), MAX_BPS, true, e),
        amount(field + ".flatMinimumPaise", req.flatMinimumPaise(), false, e));
  }

  private static List<CustomFee> fees(String field, List<FeeReq> reqs, List<FieldError> e) {
    if (reqs == null || reqs.isEmpty()) {
      return List.of();
    }
    if (reqs.size() > MAX_FEES) {
      e.add(new FieldError(field, "TOO_MANY", "At most " + MAX_FEES + " fees"));
      return List.of();
    }
    List<CustomFee> out = new ArrayList<>(reqs.size());
    for (int i = 0; i < reqs.size(); i++) {
      FeeReq f = reqs.get(i);
      String at = field + "[" + i + "]";
      if (f == null) {
        e.add(new FieldError(at, "REQUIRED", "A fee"));
        continue;
      }
      String id = Inputs.text(at + ".id", f.id(), 64, true, e);
      String name = Inputs.text(at + ".name", f.name(), 80, true, e);
      AppliesTo appliesTo = enumValue(at + ".appliesTo", f.appliesTo(), AppliesTo.class, e);
      AmountType amountType = enumValue(at + ".amountType", f.amountType(), AmountType.class, e);
      if (appliesTo == null || amountType == null) {
        continue;
      }
      if (amountType == AmountType.FLAT) {
        out.add(CustomFee.flat(id, name, appliesTo, amount(at + ".flatPaise", f.flatPaise(), true, e)));
      } else {
        out.add(CustomFee.percent(id, name, appliesTo, bps(at + ".percentBps", f.percentBps(), MAX_BPS, true, e)));
      }
    }
    return out;
  }

  private static MinimumDueTerms minimumDueTerms(String field, MinimumDueTermsReq req, List<FieldError> e) {
    if (req == null) {
      e.add(new FieldError(field, "REQUIRED", "Minimum-due terms"));
      return null;
    }
    FormulaType type = enumValue(field + ".formulaType", req.formulaType(), FormulaType.class, e);
    if (type == null) {
      return null;
    }
    return switch (type) {
      case FLAT -> MinimumDueTerms.flat(amount(field + ".flatAmountPaise", req.flatAmountPaise(), true, e));
      case PERCENT_OF_OUTSTANDING -> MinimumDueTerms.percentOfOutstanding(
          bps(field + ".percentBps", req.percentBps(), MAX_BPS, true, e),
          amount(field + ".flatFloorPaise", req.flatFloorPaise(), true, e));
      case HIGHER_OF -> MinimumDueTerms.higherOf(bps(field + ".percentBps", req.percentBps(), MAX_BPS, true, e),
          amount(field + ".flatAmountPaise", req.flatAmountPaise(), true, e));
      case FINANCE_CHARGE_CONDITIONAL -> MinimumDueTerms.financeChargeConditional(
          amount(field + ".flatFloorPaise", req.flatFloorPaise(), true, e),
          bps(field + ".retailCashPercentBps", req.retailCashPercentBps(), MAX_BPS, true, e),
          amount(field + ".roundUpToNearestPaise", req.roundUpToNearestPaise(), false, e));
    };
  }

  private static <T extends Enum<T>> T enumValue(String field, String v, Class<T> type, List<FieldError> e) {
    if (v == null || v.isBlank()) {
      e.add(new FieldError(field, "REQUIRED", "One of " + names(type)));
      return null;
    }
    try {
      return Enum.valueOf(type, v.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      e.add(new FieldError(field, "INVALID", "One of " + names(type)));
      return null;
    }
  }

  private static <T extends Enum<T>> String names(Class<T> type) {
    List<String> out = new ArrayList<>();
    for (T c : type.getEnumConstants()) {
      out.add(c.name());
    }
    return String.join(", ", out);
  }
}
