package in.dexter.planning;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.planning.support.PlanningIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * /v1/liabilities (MASTER_PLAN P13): create with the schedule computed once by the shared EMI engine, the schedule
 * as a keyset page, patch, close, and the {@code planning.liability.changed} event on every change.
 */
class LiabilitiesIT extends PlanningIT {

  @Test
  void createGivesTheSharedEnginesInstalmentAndStoresTheWholeSchedule() {
    UUID a = UUID.randomUUID();
    Res created = post("/v1/liabilities", a,
        liability("loan", "HDFC Bank", 10_000_000L, 1200, 12, "2026-02-01"));

    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    JsonNode l = created.json();
    // the golden fixture MASTER_PLAN §12 assigns to P13: Rs 1,00,000 at 12 % over 12 months is Rs 8,884.88
    assertThat(l.get("emiPaise").asLong()).isEqualTo(888_488L);
    assertThat(l.get("tenureMonths").asInt()).isEqualTo(12);
    assertThat(l.get("startDate").asString()).isEqualTo("2026-02-01");
    assertThat(l.get("lastDueDate").asString()).isEqualTo("2027-01-01");
    assertThat(l.get("status").asString()).isEqualTo("active");
    assertThat(l.get("totalPayablePaise").asLong())
        .isEqualTo(10_000_000L + l.get("totalInterestPaise").asLong());
    assertThat(created.header("ETag")).isEqualTo("\"0\"");
    String id = l.get("id").asString();

    Res first = get("/v1/liabilities/" + id + "/schedule?limit=5", a);
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.json().get("data").size()).isEqualTo(5);
    assertThat(first.json().get("data").get(0).get("number").asInt()).isEqualTo(1);
    assertThat(first.json().get("data").get(0).get("dueOn").asString()).isEqualTo("2026-02-01");
    assertThat(first.json().get("data").get(0).get("totalPaise").asLong())
        .as("a loan carries no GST").isEqualTo(first.json().get("data").get(0).get("principalPaise").asLong()
            + first.json().get("data").get(0).get("interestPaise").asLong());
    String cursor = first.json().get("page").get("nextCursor").asString();

    Res rest = get("/v1/liabilities/" + id + "/schedule?limit=10&cursor="
        + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8), a);
    assertThat(rest.json().get("data").size()).isEqualTo(7);
    assertThat(rest.json().get("data").get(6).get("outstandingAfterPaise").asLong())
        .as("the schedule closes at zero").isZero();
    assertThat(rest.json().get("page").get("hasMore").asBoolean()).isFalse();

    List<String> events = outbox(a, "planning.liability.changed");
    assertThat(events).hasSize(1);
    assertThat(events.get(0)).contains("\"emiPaise\":888488").contains("\"status\":\"active\"")
        .contains("\"lender\":\"HDFC Bank\"");
  }

  @Test
  void patchAndCloseEachPublishAChange() {
    UUID a = UUID.randomUUID();
    UUID id = newLiability(a, liability("bnpl", "Simpl", 500_000L, 0, 3, "2026-03-01"));
    assertThat(get("/v1/liabilities/" + id, a).json().get("emiPaise").asLong())
        .as("zero interest splits the principal").isEqualTo(166_667L);

    Res renamed = patch("/v1/liabilities/" + id, a, "{\"lender\":\"Simpl Pay\"}", "\"0\"");
    assertThat(renamed.status()).as(renamed.toString()).isEqualTo(200);
    assertThat(renamed.json().get("lender").asString()).isEqualTo("Simpl Pay");
    assertThat(renamed.header("ETag")).isEqualTo("\"1\"");

    assertThat(delete("/v1/liabilities/" + id, a).status()).isEqualTo(204);
    assertThat(get("/v1/liabilities/" + id, a).json().get("status").asString()).isEqualTo("closed");
    assertThat(delete("/v1/liabilities/" + id, a).status()).as("closing twice changes nothing").isEqualTo(204);

    Res reopen = patch("/v1/liabilities/" + id, a, "{\"status\":\"active\"}", "\"2\"");
    assertThat(reopen.status()).isEqualTo(409);
    assertThat(reopen.code()).isEqualTo("LIABILITY_NOT_ACTIVE");

    assertThat(outbox(a, "planning.liability.changed")).as("created, renamed, closed").hasSize(3);
  }

  @Test
  void theScheduleIsNotRewrittenAndAnotherAccountCannotSeeIt() {
    UUID a = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    UUID id = newLiability(a, liability("loan", "SBI", 10_000_000L, 1200, 12, "2026-02-01"));
    long before = ADMIN.sql("SELECT sum(total_paise) FROM planning.liability_schedule WHERE liability_id = :l")
        .param("l", id).query(Long.class).single();

    patch("/v1/liabilities/" + id, a, "{\"lender\":\"State Bank\"}", "\"0\"");

    assertThat(ADMIN.sql("SELECT sum(total_paise) FROM planning.liability_schedule WHERE liability_id = :l")
        .param("l", id).query(Long.class).single()).isEqualTo(before);
    assertThat(get("/v1/liabilities/" + id, other).status()).isEqualTo(404);
    assertThat(get("/v1/liabilities/" + id + "/schedule", other).status()).isEqualTo(404);
  }

  @Test
  void badInputIsRefused() {
    UUID a = UUID.randomUUID();

    assertThat(post("/v1/liabilities", a, liability("mortgage", "x", 1_000L, 0, 1, "2026-01-01")).body())
        .contains("kind");
    assertThat(post("/v1/liabilities", a, liability("loan", "x", 0L, 0, 1, "2026-01-01")).body())
        .contains("principalPaise");
    assertThat(post("/v1/liabilities", a, liability("loan", "x", 1_000L, 9_000, 1, "2026-01-01")).body())
        .contains("aprBps");
    assertThat(post("/v1/liabilities", a, liability("loan", "x", 1_000L, 0, 0, "2026-01-01")).body())
        .contains("tenureMonths");
    assertThat(post("/v1/liabilities", a, liability("loan", "x", 1_000L, 0, 1, "not-a-date")).body())
        .contains("startDate");
  }
}
