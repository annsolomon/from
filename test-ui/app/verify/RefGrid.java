package in.dexter.portfolio.calc;

import in.dexter.portfolio.calc.CreditUtilizationCalculator.OverlimitFeeTerms;
import in.dexter.portfolio.calc.LateFeeAndDelinquencyCalculator.LateFeeTier;
import in.dexter.portfolio.calc.MinimumDueCalculator.MinimumDueInputs;
import in.dexter.portfolio.calc.MinimumDueCalculator.MinimumDueTerms;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Reference outputs of the REAL repo engines, used to verify the JS ports bit-for-bit (paise). */
public final class RefGrid {
  static StringBuilder out = new StringBuilder();
  static boolean first = true;

  static void row(String kind, String in, String res) {
    out.append(first ? "" : ",\n").append("{\"k\":\"").append(kind).append("\",\"in\":").append(in)
        .append(",\"out\":").append(res).append("}");
    first = false;
  }

  static String n(Object o) { return o == null ? "null" : String.valueOf(o); }

  static String terms(LedgerTerms t) {
    StringBuilder b = new StringBuilder("{\"aprBps\":" + t.aprBps() + ",\"cashAdvanceAprBps\":" + t.cashAdvanceAprBps()
        + ",\"order\":[");
    for (int i = 0; i < t.paymentAllocationOrder().size(); i++) {
      b.append(i == 0 ? "" : ",").append('"').append(t.paymentAllocationOrder().get(i)).append('"');
    }
    return b.append("]}").toString();
  }

  static String mdt(MinimumDueTerms m) {
    return "{\"formulaType\":\"" + m.formulaType() + "\",\"flatAmountPaise\":" + n(m.flatAmountPaise())
        + ",\"percentBps\":" + n(m.percentBps()) + ",\"flatFloorPaise\":" + n(m.flatFloorPaise())
        + ",\"retailCashPercentBps\":" + n(m.retailCashPercentBps()) + ",\"roundUpToNearestPaise\":"
        + n(m.roundUpToNearestPaise()) + "}";
  }

  public static void main(String[] a) {
    out.append("[\n");
    // ---------- EMI ----------
    long[] principals = {100000, 2500000, 8888488, 10000000, 30000000, 123456789};
    int[] rates = {0, 1300, 1500, 1599, 1800, 2400, 4284};
    int[] tenures = {1, 3, 6, 9, 12, 18, 24, 36};
    for (long p : principals) for (int r : rates) for (int t : tenures) for (int feeMode = 0; feeMode < 3; feeMode++) {
      long fee = feeMode == 0 ? 0 : feeMode == 1 ? 19900 : p / 100;
      EmiMath.Schedule s = EmiMath.schedule(p, r, t, 1800, fee, LocalDate.of(2026, 12, 5));
      long sumTotal = 0, sumPrin = 0;
      StringBuilder inst = new StringBuilder("[");
      for (EmiMath.Installment i : s.installments()) {
        sumTotal += i.totalPaise(); sumPrin += i.principalPaise();
        inst.append(inst.length() == 1 ? "" : ",").append("[").append(i.principalPaise()).append(",")
            .append(i.interestPaise()).append(",").append(i.gstPaise()).append(",").append(i.totalPaise()).append(",")
            .append(i.outstandingAfterPaise()).append("]");
      }
      inst.append("]");
      row("emi", "{\"p\":" + p + ",\"r\":" + r + ",\"n\":" + t + ",\"gst\":1800,\"fee\":" + fee + "}",
          "{\"emi\":" + s.emiPaise() + ",\"ti\":" + s.totalInterestPaise() + ",\"tg\":" + s.totalGstPaise()
              + ",\"sumTotal\":" + sumTotal + ",\"sumPrin\":" + sumPrin + ",\"inst\":" + (t <= 12 ? inst : "null")
              + "}");
    }
    // ---------- flat payoff search ----------
    List<List<String>> orders = List.of(List.of("finance_charges", "fees", "retail_purchases"),
        List.of("retail_purchases", "finance_charges", "fees"));
    int[] aprs = {3600, 4284, 4800};
    long[] bals = {2500000, 10000000, 30000000};
    int[] targets = {1, 3, 6, 12, 24};
    for (int apr : aprs) for (List<String> o : orders) for (long b : bals) for (int tc : targets) {
      LedgerTerms lt = new LedgerTerms(apr, 4284, o);
      ExitPathCalculator.FoundFlatPayment f = ExitPathCalculator.findMinimumFlatPaymentForCycles(lt, b, tc, 30);
      row("flatSearch", "{\"terms\":" + terms(lt) + ",\"b\":" + b + ",\"t\":" + tc + "}",
          f == null ? "null" : "{\"pay\":" + f.paymentPaise() + ",\"cleared\":" + n(f.clearedByCycle()) + ",\"ti\":"
              + f.totalInterestPaise() + ",\"tg\":" + f.totalGstPaise() + "}");
    }
    long[] pays = {300000, 450000, 900000, 1234567};
    for (int apr : aprs) for (long b : bals) for (long pay : pays) {
      LedgerTerms lt = new LedgerTerms(apr, 4284, orders.get(0));
      ExitPathCalculator.FlatPaymentResult r = ExitPathCalculator.simulateFlatPayment(lt, b, pay, 120, 30);
      row("flatSim", "{\"terms\":" + terms(lt) + ",\"b\":" + b + ",\"pay\":" + pay + ",\"max\":120}",
          "{\"cleared\":" + n(r.clearedByCycle()) + ",\"ti\":" + r.totalInterestPaise() + ",\"tg\":"
              + r.totalGstPaise() + ",\"feasible\":" + r.feasible() + "}");
    }
    // ---------- minimum due ----------
    List<MinimumDueTerms> mds = List.of(MinimumDueTerms.flat(50000), MinimumDueTerms.percentOfOutstanding(500, 20000),
        MinimumDueTerms.higherOf(300, 10000), MinimumDueTerms.financeChargeConditional(20000, 500, 1000),
        MinimumDueTerms.financeChargeConditional(10000, 100, 10000));
    long[][] mdIn = {{2500000, 85000, 0, 0, 15300, 0, 2600300}, {10000000, 350000, 450000, 0, 63000, 0, 10863000},
        {15000, 0, 0, 0, 0, 0, 15000}, {30000000, 1050000, 1200000, 59000, 200520, 2500000, 32509520},
        {9999, 0, 0, 0, 0, 0, 9999}};
    for (MinimumDueTerms m : mds) for (long[] x : mdIn) {
      long v = MinimumDueCalculator.calculate(m, new MinimumDueInputs(x[0], x[1], x[2], x[3], x[4], x[5], x[6]));
      row("minDue", "{\"terms\":" + mdt(m) + ",\"x\":[" + x[0] + "," + x[1] + "," + x[2] + "," + x[3] + "," + x[4] + ","
          + x[5] + "," + x[6] + "]}", "{\"v\":" + v + "}");
    }
    // ---------- minimum-only simulation ----------
    List<MinimumDueTerms> simMds = List.of(MinimumDueTerms.percentOfOutstanding(500, 20000),
        MinimumDueTerms.higherOf(300, 10000), MinimumDueTerms.financeChargeConditional(20000, 500, 1000),
        MinimumDueTerms.percentOfOutstanding(200, 10000));
    for (int apr : new int[] {3600, 4284}) for (MinimumDueTerms m : simMds) for (long b : bals) {
      LedgerTerms lt = new LedgerTerms(apr, 4284, orders.get(0));
      for (int lim = 0; lim < 2; lim++) {
        Long limit = lim == 0 ? null : b - 500000;
        OverlimitFeeTerms of = lim == 0 ? null : new OverlimitFeeTerms(250, 50000);
        MinimumPaymentSimulator.MinimumPaymentResult r = MinimumPaymentSimulator.simulate(lt, m, b, 30, 360, limit, of);
        String first3 = "[";
        for (int i = 0; i < Math.min(3, r.amortizationTable().size()); i++) {
          var c = r.amortizationTable().get(i);
          first3 += (i == 0 ? "" : ",") + "[" + c.openingPrincipalPaise() + "," + c.financeChargesPaise() + ","
              + c.gstPaise() + "," + c.overlimitFeePaise() + "," + c.minimumDuePaise() + "," + n(c.paymentPaise()) + ","
              + n(c.closingPrincipalPaise()) + "]";
        }
        first3 += "]";
        row("minOnly", "{\"terms\":" + terms(lt) + ",\"md\":" + mdt(m) + ",\"b\":" + b + ",\"limit\":" + n(limit)
                + ",\"of\":" + (of == null ? "null" : "{\"percentBps\":250,\"flatMinimumPaise\":50000}") + "}",
            "{\"cycles\":" + n(r.cyclesToPayoff()) + ",\"inf\":" + r.infiniteDebtWarning() + ",\"ti\":"
                + n(r.totalInterestPaidPaise()) + ",\"tg\":" + n(r.totalGstPaidPaise()) + ",\"to\":"
                + n(r.totalOverlimitFeesPaidPaise()) + ",\"extra\":" + n(r.totalExtraPaidPaise()) + ",\"rows\":"
                + r.amortizationTable().size() + ",\"first\":" + first3 + "}");
      }
    }
    // ---------- no payment ----------
    List<LateFeeTier> tiers = List.of(new LateFeeTier(0, 50000L, 0), new LateFeeTier(50001, 500000L, 50000),
        new LateFeeTier(500001, 1000000L, 75000), new LateFeeTier(1000001, 2500000L, 95000),
        new LateFeeTier(2500001, 5000000L, 110000), new LateFeeTier(5000001, null, 130000));
    for (long b : new long[] {40000, 300000, 2500000, 10000000}) for (int lim = 0; lim < 2; lim++) {
      LedgerTerms lt = new LedgerTerms(4284, 4284, orders.get(0));
      Long limit = lim == 0 ? null : b - 20000;
      NoPaymentSimulator.NoPaymentResult r = NoPaymentSimulator.simulate(lt, tiers,
          new OverlimitFeeTerms(250, 50000), b, limit, 30);
      StringBuilder pr = new StringBuilder("[");
      for (var p : r.projections()) {
        pr.append(pr.length() == 1 ? "" : ",").append("[").append(p.daysOverdue()).append(",")
            .append(p.interestAccruedPaise()).append(",").append(p.overlimitFeesAccruedPaise()).append(",")
            .append(p.totalOutstandingPaise()).append(",\"").append(p.delinquencyStage().stage()).append("\"]");
      }
      pr.append("]");
      row("noPay", "{\"b\":" + b + ",\"limit\":" + n(limit) + "}", "{\"lateFee\":" + r.lateFeePaise() + ",\"gstLate\":"
          + r.gstOnLateFeePaise() + ",\"over\":" + r.overLimitPaise() + ",\"olImm\":" + r.overlimitFeeImmediatePaise()
          + ",\"proj\":" + pr + "}");
    }
    // ---------- grace demo via ledger ----------
    for (boolean grace : new boolean[] {true, false}) {
      LedgerTerms lt = new LedgerTerms(4284, 4284, orders.get(0));
      List<LedgerTransaction> tx = new ArrayList<>();
      tx.add(LedgerTransaction.purchase(LocalDate.of(2026, 1, 3), 1500000));
      tx.add(LedgerTransaction.purchase(LocalDate.of(2026, 1, 17), 500000));
      LedgerEngine.LedgerResult r = LedgerEngine.ledger(tx, lt, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 30),
          List.of(new LedgerEngine.LotView(LocalDate.of(2026, 1, 1), "retail_purchases", 4000000, 4284, null)), grace);
      row("ledger", "{\"grace\":" + grace + "}", "{\"ti\":" + r.totalInterestPaise() + "}");
    }
    // ---------- repayment path ----------
    var cards = List.of(new RepaymentPathCalculator.CardBalance("a", "Saffron", 8500000, 4284, 425000),
        new RepaymentPathCalculator.CardBalance("b", "Indus", 2000000, 3600, 100000),
        new RepaymentPathCalculator.CardBalance("c", "Deccan", 4500000, 4800, 225000));
    for (long budget : new long[] {800000, 1500000, 3000000}) {
      var r = RepaymentPathCalculator.findBestRepaymentPath(cards, budget);
      row("repay", "{\"budget\":" + budget + "}", "{\"rec\":\"" + r.recommendedStrategy() + "\",\"av\":["
          + r.avalanche().monthsToDebtFree() + "," + r.avalanche().totalInterestPaidPaise() + "],\"sb\":["
          + r.snowball().monthsToDebtFree() + "," + r.snowball().totalInterestPaidPaise() + "]}");
    }
    // ---------- custom / full / partial ----------
    var fees = List.of(CustomFeeCalculator.CustomFee.flat("af", "Annual", CustomFeeCalculator.AppliesTo.ONCE, 49900),
        CustomFeeCalculator.CustomFee.percent("svc", "Service", CustomFeeCalculator.AppliesTo.EVERY_CYCLE, 35));
    for (long b : new long[] {123457, 2500000, 9999999}) for (int c : new int[] {1, 2}) {
      var r = CustomFeeCalculator.computeForCycle(fees, b, c);
      row("custom", "{\"b\":" + b + ",\"c\":" + c + "}", "{\"t\":" + r.totalPaise() + ",\"g\":" + r.gstPaise() + "}");
      var f = FullPaymentCalculator.simulate(b, fees);
      row("full", "{\"b\":" + b + ",\"c\":" + c + "}", "{\"tot\":" + f.totalCostPaise() + "}");
    }
    for (long pay : new long[] {500000, 1000000, 1999999, 2600000}) for (boolean g : new boolean[] {true, false}) {
      LedgerTerms lt = new LedgerTerms(4284, 4284, orders.get(0));
      var tx = List.of(LedgerTransaction.purchase(LocalDate.of(2026, 1, 3), 1500000),
          LedgerTransaction.purchase(LocalDate.of(2026, 1, 17), 500000));
      var nx = List.of(LedgerTransaction.purchase(LocalDate.of(2026, 2, 5), 800000));
      var r = PartialPaymentCalculator.simulate(lt, tx, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 30), pay, g, nx,
          LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 1));
      row("partial", "{\"pay\":" + pay + ",\"g\":" + g + "}", "{\"sc\":\"" + r.scenario() + "\",\"sb\":" + r.statementBalancePaise()
          + ",\"i\":" + r.interestChargedThisCyclePaise() + ",\"c\":" + r.carriedPrincipalIntoNextCyclePaise() + ",\"n\":"
          + n(r.nextCycleInterestIfNoFurtherPaymentPaise()) + "}");
    }
    out.append("\n]\n");
    System.out.print(out);
  }
}
