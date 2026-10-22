package in.dexter.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.portfolio.support.PortfolioIT;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** /v1/emi-plans (MASTER_PLAN P11): create with the computed schedule, keyset schedule, If-Match patch, close. */
class EmiIT extends PortfolioIT {

  static Map<String, Object> plan(UUID card, long principal, int rate, int months) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("userCardId", card);
    m.put("description", "Phone on EMI");
    m.put("principalPaise", principal);
    m.put("annualRateBps", rate);
    m.put("tenureMonths", months);
    m.put("processingFeePaise", 19_900);
    m.put("firstDueDate", "2026-11-05");
    return m;
  }

  @Test
  void createReadSchedulePatchClose() {
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, POINTS_CARD);
    String txn = record(a, card, txn("2026-10-01T10:00:00Z", 10_000_000, "purchase", "CROMA", "shopping.electronics"))
        .json().get("id").asString();
    Map<String, Object> body = plan(card, 10_000_000, 1500, 12);
    body.put("cardTxnId", txn);
    Res created = post("/v1/emi-plans", a, body);
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    JsonNode p = created.json();
    assertThat(p.get("emiPaise").asLong()).isEqualTo(902_583);
    assertThat(p.get("totalInterestPaise").asLong()).isEqualTo(830_997);
    assertThat(p.get("lastDueDate").asString()).isEqualTo("2027-10-05");
    assertThat(p.get("totalPayablePaise").asLong()).isEqualTo(10_000_000 + 830_997 + p.get("totalGstPaise").asLong()
        + 19_900);
    String id = p.get("id").asString();

    JsonNode s1 = get("/v1/emi-plans/" + id + "/schedule?limit=5", a).json();
    assertThat(s1.get("data").get(0).get("number").asInt()).isEqualTo(1);
    assertThat(s1.get("data").get(0).get("interestPaise").asLong()).isEqualTo(125_000);
    String cursor = s1.get("page").get("nextCursor").asString();
    JsonNode s2 = get("/v1/emi-plans/" + id + "/schedule?limit=10&cursor=" + java.net.URLEncoder.encode(cursor,
        java.nio.charset.StandardCharsets.UTF_8), a).json();
    assertThat(s2.get("data").size()).isEqualTo(7);
    assertThat(s2.get("data").get(6).get("outstandingAfterPaise").asLong()).isZero();
    assertThat(s2.get("page").get("hasMore").asBoolean()).isFalse();

    assertThat(patch("/v1/emi-plans/" + id, a, "{\"description\":\"iPhone\"}", "\"0\"").header("ETag"))
        .isEqualTo("\"1\"");
    assertThat(patch("/v1/emi-plans/" + id, a, "{\"principalPaise\":1}", "\"1\"").code()).isEqualTo("VALIDATION_FAILED");
    assertThat(patch("/v1/emi-plans/" + id, a, "{\"status\":\"foreclosed\"}", "\"1\"").json().get("status").asString())
        .isEqualTo("foreclosed");
    assertThat(patch("/v1/emi-plans/" + id, a, "{\"status\":\"active\"}", "\"2\"").code()).isEqualTo("EMI_NOT_ACTIVE");
    assertThat(ADMIN.sql("SELECT count(1) FROM portfolio.emi_schedule WHERE plan_id = CAST(:p AS uuid)"
        + " AND status = 'cancelled'").param("p", id).query(Long.class).single()).isEqualTo(12);
    assertThat(delete("/v1/emi-plans/" + id, a).status()).as("closing an ended plan is a no-op").isEqualTo(204);
    assertThat(get("/v1/emi-plans", a).json().get("data").get(0).get("id").asString()).isEqualTo(id);
    assertThat(get("/v1/emi-plans/" + id, UUID.randomUUID()).code()).isEqualTo("NOT_FOUND");
  }

  @Test
  void validation() {
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, CASHBACK_CARD);
    Map<String, Object> bad = plan(card, 50_000, 7000, 0);
    bad.put("gstBps", 5000);
    assertThat(post("/v1/emi-plans", a, bad).json().get("errors")).extracting(e -> e.get("field").asString())
        .contains("principalPaise", "annualRateBps", "tenureMonths", "gstBps");
    assertThat(post("/v1/emi-plans", a, plan(UUID.randomUUID(), 1_000_000, 1500, 6)).json().get("errors").get(0)
        .get("code").asString()).isEqualTo("UNKNOWN_CARD");
    Map<String, Object> noDue = plan(card, 1_000_000, 0, 3);
    noDue.remove("firstDueDate");
    JsonNode z = post("/v1/emi-plans", a, noDue).json();
    assertThat(z.get("emiPaise").asLong()).isEqualTo(333_333);
    assertThat(z.get("firstDueDate").isNull()).as("defaults to the card's next due date").isFalse();
  }
}
