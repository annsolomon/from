package in.dexter.planning;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.planning.support.PlanningIT;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** /v1/budgets (MASTER_PLAN P13): create, read one, list, status, history, patch with If-Match, end. */
class BudgetsIT extends PlanningIT {

  @Test
  void createReadListPatchEnd() {
    UUID a = UUID.randomUUID();
    Res created = post("/v1/budgets", a, budget("Eating out", "monthly", "food.dining", 1_000_000L, "2026-01-01"));

    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    JsonNode b = created.json();
    assertThat(b.get("name").asString()).isEqualTo("Eating out");
    assertThat(b.get("categoryPath").asString()).isEqualTo("food.dining");
    assertThat(b.get("limitPaise").asLong()).isEqualTo(1_000_000L);
    assertThat(b.get("alertThresholds").toString()).as("the plan's default").isEqualTo("[50,80,100]");
    assertThat(b.get("status").asString()).isEqualTo("active");
    assertThat(created.header("ETag")).isEqualTo("\"0\"");
    assertThat(created.header("Location")).endsWith("/v1/budgets/" + b.get("id").asString());
    String id = b.get("id").asString();

    // GET /v1/budgets/{id} — the plan calls it out because the old repo had no read-one
    Res one = get("/v1/budgets/" + id, a);
    assertThat(one.status()).isEqualTo(200);
    assertThat(one.header("ETag")).isEqualTo("\"0\"");
    assertThat(one.json().get("id").asString()).isEqualTo(id);

    Res list = get("/v1/budgets?limit=10", a);
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.json().get("data").size()).isEqualTo(1);
    assertThat(list.json().get("page").get("hasMore").asBoolean()).isFalse();

    // If-Match is required, and must match
    assertThat(patch("/v1/budgets/" + id, a, "{\"limitPaise\":2000000}", null).status()).isEqualTo(428);
    assertThat(patch("/v1/budgets/" + id, a, "{\"limitPaise\":2000000}", "\"7\"").status()).isEqualTo(412);

    Res patched = patch("/v1/budgets/" + id, a,
        "{\"name\":\"Dining\",\"limitPaise\":2000000,\"alertThresholds\":[90,25,25],\"rollover\":true}", "\"0\"");
    assertThat(patched.status()).as(patched.toString()).isEqualTo(200);
    assertThat(patched.json().get("name").asString()).isEqualTo("Dining");
    assertThat(patched.json().get("limitPaise").asLong()).isEqualTo(2_000_000L);
    assertThat(patched.json().get("alertThresholds").toString()).as("sorted and de-duplicated")
        .isEqualTo("[25,90]");
    assertThat(patched.json().get("rollover").asBoolean()).isTrue();
    assertThat(patched.header("ETag")).isEqualTo("\"1\"");

    // a patch that changes nothing keeps the version
    Res again = patch("/v1/budgets/" + id, a, "{\"name\":\"Dining\"}", "\"1\"");
    assertThat(again.json().get("version").asInt()).isEqualTo(1);

    assertThat(delete("/v1/budgets/" + id, a).status()).isEqualTo(204);
    assertThat(get("/v1/budgets/" + id, a).json().get("status").asString()).isEqualTo("ended");
    // an ended budget is read-only
    Res reopen = patch("/v1/budgets/" + id, a, "{\"limitPaise\":5000000}", "\"2\"");
    assertThat(reopen.status()).isEqualTo(409);
    assertThat(reopen.code()).isEqualTo("BUDGET_NOT_ACTIVE");
    assertThat(delete("/v1/budgets/" + id, a).status()).as("ending twice changes nothing").isEqualTo(204);
  }

  @Test
  void statusShowsTheCurrentPeriodOfEveryActiveBudget() {
    UUID a = UUID.randomUUID();
    UUID monthly = newBudget(a, budget("Everything", "monthly", null, 5_000_000L, "2026-01-01"));
    UUID weekly = newBudget(a, budget("Coffee", "weekly", "food.dining", 100_000L, "2026-01-05"));

    Res status = get("/v1/budgets/status", a);
    assertThat(status.status()).as(status.toString()).isEqualTo(200);
    JsonNode rows = status.json();
    assertThat(rows.size()).isEqualTo(2);
    for (JsonNode row : rows) {
      JsonNode current = row.get("current");
      assertThat(current.isNull()).as("both budgets are open-ended, so today is in a period").isFalse();
      assertThat(current.get("spentPaise").asLong()).isZero();
      assertThat(current.get("usedBps").asInt()).isZero();
      assertThat(current.get("daysInPeriod").asInt()).isPositive();
      assertThat(current.get("thresholdsCrossed").size()).isZero();
    }
    assertThat(rows.get(0).get("budget").get("id").asString()).isEqualTo(monthly.toString());
    assertThat(rows.get(1).get("budget").get("id").asString()).isEqualTo(weekly.toString());

    // a budget whose window has closed has no current period
    UUID past = newBudget(a, Map.of("name", "Last year", "period", "custom", "limitPaise", 100_000L,
        "startsOn", "2025-01-01", "endsOn", "2025-12-31"));
    JsonNode after = get("/v1/budgets/status", a).json();
    assertThat(after.size()).isEqualTo(3);
    JsonNode closed = null;
    for (JsonNode row : after) {
      if (row.get("budget").get("id").asString().equals(past.toString())) {
        closed = row;
      }
    }
    assertThat(closed).isNotNull();
    assertThat(closed.get("current").isNull()).isTrue();
  }

  @Test
  void aReplayOfTheSameKeyReturnsTheSameBudget() {
    UUID a = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    Map<String, Object> body = budget("Travel", "monthly", "travel", 3_000_000L, "2026-01-01");

    Res first = post("/v1/budgets", a, body, key);
    Res replay = post("/v1/budgets", a, body, key);

    assertThat(first.status()).isEqualTo(201);
    assertThat(replay.status()).isEqualTo(201);
    assertThat(replay.json().get("id").asString()).isEqualTo(first.json().get("id").asString());
    assertThat(get("/v1/budgets?limit=10", a).json().get("data").size()).isEqualTo(1);
  }

  @Test
  void anotherAccountCannotSeeOrChangeIt() {
    UUID a = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID id = newBudget(a, budget("Mine", "monthly", null, 1_000_000L, "2026-01-01"));

    assertThat(get("/v1/budgets/" + id, other).status()).isEqualTo(404);
    assertThat(patch("/v1/budgets/" + id, other, "{\"limitPaise\":1}", "\"0\"").status()).isEqualTo(404);
    assertThat(delete("/v1/budgets/" + id, other).status()).isEqualTo(404);
    assertThat(get("/v1/budgets?limit=10", other).json().get("data").size()).isZero();
    assertThat(get("/v1/budgets/status", other).json().size()).isZero();
  }

  @Test
  void anonymousAndAdminTokensAreRefused() {
    assertThat(send("GET", "/v1/budgets", null, null).status()).isEqualTo(401);
    Res admin = send("GET", "/v1/budgets", null, null, "Authorization",
        "Bearer " + IDP.admin(UUID.randomUUID()).audience("planning").sign());
    assertThat(admin.status()).isEqualTo(403);
    assertThat(admin.code()).isEqualTo("USER_TOKEN_REQUIRED");
  }

  @Test
  void badInputIsRefusedWithFieldErrors() {
    UUID a = UUID.randomUUID();

    Res noName = post("/v1/budgets", a, Map.of("period", "monthly", "limitPaise", 1_000L));
    assertThat(noName.status()).isEqualTo(400);
    assertThat(noName.code()).isEqualTo("VALIDATION_FAILED");
    assertThat(noName.body()).contains("name");

    assertThat(post("/v1/budgets", a, budget("x", "fortnightly", null, 1_000L, "2026-01-01")).body())
        .contains("period");
    assertThat(post("/v1/budgets", a, budget("x", "monthly", "Food.Dining", 1_000L, "2026-01-01")).body())
        .contains("categoryPath");
    assertThat(post("/v1/budgets", a, budget("x", "monthly", null, 0L, "2026-01-01")).body())
        .contains("limitPaise");
    assertThat(post("/v1/budgets", a, Map.of("name", "x", "period", "custom", "limitPaise", 1_000L,
        "startsOn", "2026-01-01")).body()).as("a custom period needs an end").contains("endsOn");
    assertThat(post("/v1/budgets", a, Map.of("name", "x", "period", "monthly", "limitPaise", 1_000L,
        "startsOn", "2026-05-01", "endsOn", "2026-01-01")).body()).contains("endsOn");
    assertThat(post("/v1/budgets", a, Map.of("name", "x", "period", "monthly", "limitPaise", 1_000L,
        "startsOn", "2026-01-01", "alertThresholds", List.of(1, 2, 3, 4, 5, 6))).body())
        .contains("alertThresholds");

    UUID id = newBudget(a, budget("Real", "monthly", null, 1_000_000L, "2026-01-01"));
    assertThat(patch("/v1/budgets/" + id, a, "{\"startsOn\":\"2026-02-01\"}", "\"0\"").body())
        .as("the anchor is not patchable").contains("UNKNOWN_FIELD");
  }
}
