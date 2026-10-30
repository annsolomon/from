package in.dexter.planning;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.planning.domain.LiabilityMath;
import in.dexter.planning.support.PlanningIT;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * /v1/scenarios (MASTER_PLAN P13): saving copies the account's active budgets and liabilities in, and
 * {@code :evaluate} applies the saved adjustments to that copy — never to a live view.
 */
class ScenariosIT extends PlanningIT {

  private static Map<String, Object> scenario(String name, long incomePaise, List<Map<String, Object>> adjustments) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("name", name);
    m.put("monthlyIncomePaise", incomePaise);
    m.put("adjustments", adjustments);
    return m;
  }

  @Test
  void savingCopiesTheAccountInAndEvaluatingAppliesTheAdjustments() {
    UUID a = UUID.randomUUID();
    newBudget(a, budget("Food", "monthly", "food", 3_000_000L, "2026-01-01"));
    UUID loan = newLiability(a, liability("loan", "HDFC", 10_000_000L, 1200, 12, "2026-02-01"));

    Res created = post("/v1/scenarios", a, scenario("Can I take a car loan?", 10_000_000L, List.of()));
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    JsonNode s = created.json();
    assertThat(s.get("baseSnapshot").get("monthlyIncomePaise").asLong()).isEqualTo(10_000_000L);
    assertThat(s.get("baseSnapshot").get("budgets").size()).isEqualTo(1);
    assertThat(s.get("baseSnapshot").get("liabilities").size()).isEqualTo(1);
    assertThat(s.get("baseSnapshot").get("liabilities").get(0).get("emiPaise").asLong()).isEqualTo(888_488L);
    String id = s.get("id").asString();

    Res evaluated = post("/v1/scenarios/" + id + ":evaluate", a, null);
    assertThat(evaluated.status()).as(evaluated.toString()).isEqualTo(200);
    JsonNode e = evaluated.json();
    assertThat(e.get("monthlyOutgoBeforePaise").asLong()).isEqualTo(3_000_000L + 888_488L);
    assertThat(e.get("deltaPaise").asLong()).isZero();
    assertThat(e.get("feasible").asBoolean()).isTrue();
    assertThat(e.get("lines").size()).isEqualTo(2);

    // add a hypothetical car loan and re-evaluate
    Res patched = patch("/v1/scenarios/" + id, a,
        "{\"adjustments\":[{\"kind\":\"addLiability\",\"lender\":\"Car loan\",\"principalPaise\":50000000,"
            + "\"aprBps\":1000,\"tenureMonths\":60}]}", "\"0\"");
    assertThat(patched.status()).as(patched.toString()).isEqualTo(200);
    assertThat(patched.json().get("adjustments").size()).isEqualTo(1);

    JsonNode after = post("/v1/scenarios/" + id + ":evaluate", a, null).json();
    long carEmi = LiabilityMath.emi(50_000_000L, 1000, 60);
    assertThat(after.get("deltaPaise").asLong()).as("the shared EMI engine, as a real liability would use")
        .isEqualTo(carEmi);
    assertThat(after.get("monthlyOutgoAfterPaise").asLong()).isEqualTo(3_000_000L + 888_488L + carEmi);
    assertThat(after.get("lines").size()).isEqualTo(3);

    // the snapshot does not follow the account: closing the real liability leaves the scenario as saved
    assertThat(delete("/v1/liabilities/" + loan, a).status()).isEqualTo(204);
    JsonNode unchanged = post("/v1/scenarios/" + id + ":evaluate", a, null).json();
    assertThat(unchanged.get("monthlyOutgoBeforePaise").asLong()).as("copy-on-read, never a live view")
        .isEqualTo(3_000_000L + 888_488L);
  }

  @Test
  void adjustmentsCanRemoveALiabilityAndChangeIncome() {
    UUID a = UUID.randomUUID();
    newBudget(a, budget("Food", "monthly", "food", 3_000_000L, "2026-01-01"));
    newLiability(a, liability("loan", "HDFC", 10_000_000L, 1200, 12, "2026-02-01"));

    Res created = post("/v1/scenarios", a, scenario("Pay it off", 10_000_000L, List.of()));
    String id = created.json().get("id").asString();
    String liabilityId = created.json().get("baseSnapshot").get("liabilities").get(0).get("id").asString();

    patch("/v1/scenarios/" + id, a, "{\"adjustments\":[{\"kind\":\"removeLiability\",\"liabilityId\":\""
        + liabilityId + "\"},{\"kind\":\"setMonthlyIncome\",\"amountPaise\":2000000}]}", "\"0\"");

    JsonNode e = post("/v1/scenarios/" + id + ":evaluate", a, null).json();
    assertThat(e.get("deltaPaise").asLong()).isEqualTo(-888_488L);
    assertThat(e.get("monthlyIncomePaise").asLong()).isEqualTo(2_000_000L);
    assertThat(e.get("surplusBeforePaise").asLong()).as("before uses the snapshot's own income")
        .isEqualTo(10_000_000L - 3_000_000L - 888_488L);
    assertThat(e.get("feasible").asBoolean()).as("Rs 20,000 does not cover a Rs 30,000 budget").isFalse();
  }

  @Test
  void anAdjustmentTheSnapshotCannotSatisfyIsRefused() {
    UUID a = UUID.randomUUID();
    newBudget(a, budget("Food", "monthly", "food", 3_000_000L, "2026-01-01"));

    Res bad = post("/v1/scenarios", a, scenario("Nope", 1_000_000L,
        List.of(Map.of("kind", "removeLiability", "liabilityId", UUID.randomUUID().toString()))));
    assertThat(bad.status()).isEqualTo(422);
    assertThat(bad.code()).isEqualTo("SCENARIO_ADJUSTMENT_INVALID");

    Res unknownKind = post("/v1/scenarios", a, scenario("Nope", 1_000_000L,
        List.of(Map.of("kind", "winTheLottery"))));
    assertThat(unknownKind.status()).isEqualTo(422);

    Res noKind = post("/v1/scenarios", a, scenario("Nope", 1_000_000L, List.of(Map.of("amountPaise", 1))));
    assertThat(noKind.status()).isEqualTo(400);
    assertThat(noKind.body()).contains("kind");
  }

  @Test
  void listAndCrossTenantReads() {
    UUID a = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    Res created = post("/v1/scenarios", a, scenario("Mine", 1_000_000L, List.of()));
    String id = created.json().get("id").asString();

    assertThat(get("/v1/scenarios?limit=10", a).json().get("data").size()).isEqualTo(1);
    assertThat(get("/v1/scenarios/" + id, a).header("ETag")).isEqualTo("\"0\"");
    assertThat(get("/v1/scenarios/" + id, other).status()).isEqualTo(404);
    assertThat(post("/v1/scenarios/" + id + ":evaluate", other, null).status()).isEqualTo(404);
    assertThat(get("/v1/scenarios?limit=10", other).json().get("data").size()).isZero();
    assertThat(post("/v1/scenarios/" + id + ":explode", a, null).status()).isEqualTo(404);
  }
}
