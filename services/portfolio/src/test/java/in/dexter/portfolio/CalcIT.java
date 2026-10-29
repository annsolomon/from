package in.dexter.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.portfolio.support.PortfolioIT;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * /v1/calc/* (MASTER_PLAN P12 step 2, ADR-0018): the ported engines over HTTP. The expected numbers are the old
 * repo's own golden-fixture values in paise — the same ones `libs/portfolio-calc` asserts as a unit — so this test
 * proves the wiring, the paise JSON contract and the caps, not the arithmetic again.
 */
class CalcIT extends PortfolioIT {

  private static final UUID ASHA = UUID.randomUUID();

  private static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static Map<String, Object> terms(int aprBps, Object... buckets) {
    return map("aprBps", aprBps, "cashAdvanceAprBps", aprBps, "paymentAllocationOrder", List.of(buckets));
  }

  /** POST without an Idempotency-Key: calc is stateless, so no endpoint requires one. */
  private Res calc(String path, Object body) {
    return send("POST", CALC + path, JSON.writeValueAsString(body), "application/json", auth(ASHA));
  }

  private static final String CALC = "/v1/calc";

  @Test
  void emiQuoteMatchesTheEmiPlanEngine() {
    Res r = calc("/emi", map("principalPaise", 10_000_000L, "annualRateBps", 1500, "tenureMonths", 12,
        "processingFeePaise", 19_900L, "firstDueDate", "2026-11-05"));

    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode s = r.json();
    assertThat(s.get("emiPaise").asLong()).isEqualTo(902_583L);
    assertThat(s.get("totalInterestPaise").asLong()).isEqualTo(830_997L);
    assertThat(s.get("installments").size()).isEqualTo(12);
    assertThat(s.get("installments").get(11).get("outstandingAfterPaise").asLong()).isZero();
  }

  @Test
  void ledgerAccruesTheCycleInterestAndReturnsTheClosingLots() {
    Res r = calc("/ledger", map("terms", terms(4200, "retail_purchases", "cash_advances"),
        "transactions", List.of(
            map("date", "2026-01-01", "type", "PURCHASE", "amountPaise", 500_000L, "bucket", "retail_purchases",
                "referenceNumber", "TXN-1"),
            map("date", "2026-01-10", "type", "PAYMENT", "amountPaise", 200_000L)),
        "cycleStart", "2026-01-01", "cycleEnd", "2026-01-30"));

    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().get("totalInterestPaise").asLong()).isEqualTo(12_427L);
    assertThat(r.json().get("closingLots").size()).isEqualTo(1);
    assertThat(r.json().get("closingLots").get(0).get("remainingPaise").asLong()).isEqualTo(300_000L);
  }

  @Test
  void minimumDueRunsTheFinanceChargeConditionalFormula() {
    Res r = calc("/minimum-due", map(
        "terms", map("formulaType", "FINANCE_CHARGE_CONDITIONAL", "flatFloorPaise", 20_000L,
            "retailCashPercentBps", 500, "roundUpToNearestPaise", 1_000L),
        "retailCashOutstandingPaise", 800_000L, "financeChargesPaise", 15_000L, "gstAmountPaise", 2_700L,
        "totalAmountDuePaise", 817_700L));

    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().get("minimumDuePaise").asLong()).isEqualTo(43_700L);
  }

  @Test
  void lateFeeTierAndRbiStageComeBackTogether() {
    Res r = calc("/late-fee", map("outstandingBalancePaise", 320_000L, "daysOverdue", 15, "tiers", List.of(
        map("minOutstandingPaise", 0L, "maxOutstandingPaise", 10_000L, "lateFeePaise", 0L),
        map("minOutstandingPaise", 100_100L, "maxOutstandingPaise", 500_000L, "lateFeePaise", 60_000L))));

    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().get("lateFeePaise").asLong()).isEqualTo(60_000L);
    assertThat(r.json().get("gstOnLateFeePaise").asLong()).isEqualTo(10_800L);
    assertThat(r.json().get("delinquency").get("stage").asString()).isEqualTo("SMA_0");
    assertThat(r.json().get("delinquency").get("reportedToBureaus").asBoolean()).isTrue();
  }

  @Test
  void utilizationReportsOverLimitAndTheFee() {
    Res r = calc("/utilization", map("spentPaise", 12_000_000L, "creditLimitPaise", 10_000_000L,
        "overlimitFee", map("percentBps", 250, "flatMinimumPaise", 55_000L)));

    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode u = r.json().get("utilization");
    assertThat(u.get("creditLimitConfigured").asBoolean()).isTrue();
    assertThat(u.get("overLimitPaise").asLong()).isEqualTo(2_000_000L);
    assertThat(u.get("utilizationBps").asInt()).isEqualTo(12_000);
    assertThat(u.get("availableCreditPaise").asLong()).isZero();
    assertThat(r.json().get("overlimitFeePaise").asLong()).isEqualTo(55_000L);
  }

  @Test
  void customFeesChargeTheOneOffOnlyOnTheFirstCycle() {
    List<Map<String, Object>> fees = List.of(
        map("id", "f1", "name", "Annual", "appliesTo", "ONCE", "amountType", "FLAT", "flatPaise", 50_000L),
        map("id", "f2", "name", "Service", "appliesTo", "EVERY_CYCLE", "amountType", "PERCENT", "percentBps", 200));

    Res one = calc("/custom-fees", map("fees", fees, "balancePaise", 1_000_000L, "cycleNumber", 1));
    assertThat(one.status()).as(one.toString()).isEqualTo(200);
    assertThat(one.json().get("totalPaise").asLong()).isEqualTo(70_000L);
    assertThat(one.json().get("gstPaise").asLong()).isEqualTo(12_600L);
    assertThat(one.json().get("breakdown").size()).isEqualTo(2);

    Res two = calc("/custom-fees", map("fees", fees, "balancePaise", 1_000_000L, "cycleNumber", 2));
    assertThat(two.json().get("totalPaise").asLong()).isEqualTo(20_000L);
    assertThat(two.json().get("breakdown").size()).isEqualTo(1);
  }

  @Test
  void theFourScenariosAnswerInPaise() {
    Res full = calc("/scenarios/full-payment", map("statementBalancePaise", 500_000L, "customFees", List.of(
        map("id", "f1", "name", "Annual", "appliesTo", "ONCE", "amountType", "FLAT", "flatPaise", 50_000L))));
    assertThat(full.status()).as(full.toString()).isEqualTo(200);
    assertThat(full.json().get("totalCostPaise").asLong()).isEqualTo(559_000L);
    assertThat(full.json().get("gracePreservedNextCycle").asBoolean()).isTrue();

    Res partial = calc("/scenarios/partial-payment", map("terms", terms(4200, "retail_purchases"),
        "transactions", List.of(map("date", "2026-01-01", "type", "PURCHASE", "amountPaise", 500_000L)),
        "cycleStart", "2026-01-01", "cycleEnd", "2026-01-30", "paymentPaise", 200_000L));
    assertThat(partial.status()).as(partial.toString()).isEqualTo(200);
    assertThat(partial.json().get("scenario").asString()).isEqualTo("partial_payment");
    assertThat(partial.json().get("statementBalancePaise").asLong()).isEqualTo(517_260L);
    assertThat(partial.json().get("carriedPrincipalIntoNextCyclePaise").asLong()).isEqualTo(300_000L);
    assertThat(partial.json().get("gracePreservedNextCycle").asBoolean()).isFalse();

    Res none = calc("/scenarios/no-payment", map("terms", terms(4200, "retail_purchases"),
        "statementBalancePaise", 500_000L, "cycleLengthDays", 30, "lateFeeTiers", List.of(
            map("minOutstandingPaise", 100_100L, "maxOutstandingPaise", 500_000L, "lateFeePaise", 60_000L))));
    assertThat(none.status()).as(none.toString()).isEqualTo(200);
    assertThat(none.json().get("lateFeePaise").asLong()).isEqualTo(60_000L);
    assertThat(none.json().get("projections").size()).isEqualTo(3);
    assertThat(none.json().get("projections").get(0).get("interestAccruedPaise").asLong()).isEqualTo(17_260L);
    assertThat(none.json().get("projections").get(0).get("totalOutstandingPaise").asLong()).isEqualTo(591_167L);
    assertThat(none.json().get("projections").get(2).get("delinquencyStage").get("stage").asString())
        .isEqualTo("SMA_2");

    // The 141-cycle case MASTER_PLAN P12 names: minimum-only on Rs 10,000 at 42 %
    Res minimum = calc("/scenarios/minimum-payment", map(
        "terms", terms(4200, "retail_purchases", "finance_charges", "fees"),
        "minimumDueTerms", map("formulaType", "HIGHER_OF", "percentBps", 500, "flatAmountPaise", 20_000L),
        "startingPrincipalPaise", 1_000_000L, "cycleLengthDays", 30));
    assertThat(minimum.status()).as(minimum.toString()).isEqualTo(200);
    JsonNode m = minimum.json();
    assertThat(m.get("infiniteDebtWarning").asBoolean()).isFalse();
    assertThat(m.get("cyclesToPayoff").asInt()).isEqualTo(141);
    assertThat(m.get("totalInterestPaidPaise").asLong()).isEqualTo(2_611_942L);
    assertThat(m.get("totalGstPaidPaise").asLong()).isEqualTo(470_146L);
    assertThat(m.get("totalExtraPaidPaise").asLong()).isEqualTo(3_082_088L);
    assertThat(m.get("amortizationTable").size()).isEqualTo(141);
  }

  @Test
  void aMinimumDueBelowTheInterestIsReportedAsNeverClearing() {
    Res r = calc("/scenarios/minimum-payment", map(
        "terms", terms(4200, "retail_purchases", "finance_charges"),
        "minimumDueTerms", map("formulaType", "FLAT", "flatAmountPaise", 10_000L),
        "startingPrincipalPaise", 5_000_000L, "cycleLengthDays", 30));

    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().get("infiniteDebtWarning").asBoolean()).isTrue();
    assertThat(r.json().get("cyclesToPayoff").isNull()).isTrue();
    assertThat(r.json().get("amortizationTable").size()).isEqualTo(1);
  }

  @Test
  void repaymentPathComparesAvalancheAndSnowball() {
    Res r = calc("/repayment-path", map("monthlyBudgetPaise", 300_000L, "cards", List.of(
        map("id", "a", "name", "A", "balancePaise", 1_000_000L, "aprBps", 4200, "minimumDuePaise", 50_000L),
        map("id", "b", "name", "B", "balancePaise", 500_000L, "aprBps", 1800, "minimumDuePaise", 25_000L))));

    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode p = r.json();
    assertThat(p.get("feasible").asBoolean()).isTrue();
    assertThat(p.get("recommendedStrategy").asString()).isEqualTo("avalanche");
    assertThat(p.get("avalanche").get("totalInterestPaidPaise").asLong()).isEqualTo(149_911L);
    assertThat(p.get("snowball").get("totalInterestPaidPaise").asLong()).isEqualTo(194_361L);
    assertThat(p.get("interestSavingsWithBestStrategyPaise").asLong()).isEqualTo(44_450L);
    assertThat(p.get("avalanche").get("paymentOrder").get(0).asString()).isEqualTo("A");
  }

  @Test
  void repaymentPathSaysSoWhenTheBudgetCannotCoverTheMinimums() {
    Res r = calc("/repayment-path", map("monthlyBudgetPaise", 70_000L, "cards", List.of(
        map("id", "a", "name", "A", "balancePaise", 1_000_000L, "aprBps", 4200, "minimumDuePaise", 50_000L),
        map("id", "b", "name", "B", "balancePaise", 500_000L, "aprBps", 1800, "minimumDuePaise", 25_000L))));

    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().get("feasible").asBoolean()).isFalse();
    assertThat(r.json().get("message").asString()).contains("less than total minimum due");
    assertThat(r.json().get("avalanche").isNull()).isTrue();
  }

  @Test
  void exitPathsFindTheFlatPaymentAndRankTheOptions() {
    Res r = calc("/exit-paths", map("terms", terms(3600, "finance_charges", "retail_purchases"),
        "minimumDueTerms", map("formulaType", "HIGHER_OF", "percentBps", 500, "flatAmountPaise", 20_000L),
        "currentBalancePaise", 600_000L, "cycleLengthDays", 30, "targetCycles", List.of(3, 6, 12)));

    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode p = r.json();
    assertThat(p.get("payInFullNow").get("amountRequiredPaise").asLong()).isEqualTo(600_000L);
    assertThat(p.get("targetedPaths").size()).isEqualTo(3);
    JsonNode three = p.get("targetedPaths").get(0);
    assertThat(three.get("targetCycles").asInt()).isEqualTo(3);
    assertThat(three.get("requiredPaymentPerCyclePaise").asLong()).isEqualTo(214_101L);
    assertThat(three.get("cyclesToDebtFree").asInt()).isEqualTo(3);
    assertThat(three.get("totalInterestAndGstPaise").asLong()).isEqualTo(42_380L);
    assertThat(p.get("continueMinimum").get("totalExtraPaidPaise").asLong()).isEqualTo(761_255L);
    assertThat(p.get("recommendedPath").asString()).isEqualTo("targeted_payoff_3cycles");
  }

  @Test
  void aZeroBalanceHasNothingToExitFrom() {
    Res r = calc("/exit-paths", map("terms", terms(3600, "retail_purchases"),
        "minimumDueTerms", map("formulaType", "HIGHER_OF", "percentBps", 500, "flatAmountPaise", 20_000L),
        "currentBalancePaise", 0L, "cycleLengthDays", 30, "targetCycles", List.of(3)));

    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().get("message").asString()).contains("already zero");
    assertThat(r.json().get("targetedPaths").size()).isZero();
  }

  @Test
  void calcNeedsATokenAndTakesPostOnly() {
    String body = JSON.writeValueAsString(map("principalPaise", 100_000L, "annualRateBps", 1500,
        "tenureMonths", 12));

    Res anonymous = send("POST", CALC + "/emi", body, "application/json");
    assertThat(anonymous.status()).isEqualTo(401);
    assertThat(anonymous.code()).isEqualTo("UNAUTHENTICATED");

    Res wrongMethod = get(CALC + "/emi", ASHA);
    assertThat(wrongMethod.status()).isEqualTo(405);
    assertThat(wrongMethod.code()).isEqualTo("METHOD_NOT_ALLOWED");
  }

  @Test
  void badInputIsFourHundredWithFieldErrorsAndNeverFiveHundred() {
    Res noTerms = calc("/ledger", map("cycleStart", "2026-01-01", "cycleEnd", "2026-01-30"));
    assertThat(noTerms.status()).as(noTerms.toString()).isEqualTo(400);
    assertThat(noTerms.code()).isEqualTo("VALIDATION_FAILED");
    assertThat(noTerms.body()).contains("terms");

    Res badEnum = calc("/minimum-due", map("terms", map("formulaType", "SOMETHING_ELSE")));
    assertThat(badEnum.status()).isEqualTo(400);
    assertThat(badEnum.body()).contains("HIGHER_OF");

    // a formula's own required field
    Res missingField = calc("/minimum-due", map("terms", map("formulaType", "HIGHER_OF", "percentBps", 500)));
    assertThat(missingField.status()).isEqualTo(400);
    assertThat(missingField.body()).contains("flatAmountPaise");

    Res backwards = calc("/ledger", map("terms", terms(4200, "retail_purchases"),
        "cycleStart", "2026-01-31", "cycleEnd", "2026-01-01"));
    assertThat(backwards.status()).isEqualTo(400);

    Res tooLongAWindow = calc("/ledger", map("terms", terms(4200, "retail_purchases"),
        "cycleStart", "2026-01-01", "cycleEnd", "2027-06-01"));
    assertThat(tooLongAWindow.status()).isEqualTo(400);

    Res tooManyCycles = calc("/exit-paths", map("terms", terms(3600, "retail_purchases"),
        "minimumDueTerms", map("formulaType", "HIGHER_OF", "percentBps", 500, "flatAmountPaise", 20_000L),
        "currentBalancePaise", 600_000L, "cycleLengthDays", 30, "targetCycles", List.of(1, 2, 3, 4, 5, 6, 7)));
    assertThat(tooManyCycles.status()).isEqualTo(400);
    assertThat(tooManyCycles.body()).contains("targetCycles");

    Res aprTooHigh = calc("/ledger", map("terms", terms(20_000, "retail_purchases"),
        "cycleStart", "2026-01-01", "cycleEnd", "2026-01-30"));
    assertThat(aprTooHigh.status()).isEqualTo(400);
  }

  @Test
  void anOversizedBodyIsRefusedBeforeAnyWork() {
    List<Map<String, Object>> txns = new ArrayList<>();
    for (int i = 0; i < 700; i++) {
      txns.add(map("date", "2026-01-01", "type", "PURCHASE", "amountPaise", 100L,
          "bucket", "retail_purchases", "referenceNumber", "TXN-" + "0".repeat(50) + i));
    }
    Res r = calc("/ledger", map("terms", terms(4200, "retail_purchases"), "transactions", txns,
        "cycleStart", "2026-01-01", "cycleEnd", "2026-01-30"));

    assertThat(r.status()).as(r.toString()).isEqualTo(413);
    assertThat(r.code()).isEqualTo("PAYLOAD_TOO_LARGE");
  }

  @Test
  void transactionListsAreCapped() {
    List<Map<String, Object>> txns = new ArrayList<>();
    for (int i = 0; i < 501; i++) {
      txns.add(map("date", "2026-01-01", "type", "PURCHASE", "amountPaise", 100L));
    }
    Res r = calc("/ledger", map("terms", terms(4200, "retail_purchases"), "transactions", txns,
        "cycleStart", "2026-01-01", "cycleEnd", "2026-01-30"));

    assertThat(r.status()).as(r.toString()).isEqualTo(400);
    assertThat(r.body()).contains("transactions");
  }
}
