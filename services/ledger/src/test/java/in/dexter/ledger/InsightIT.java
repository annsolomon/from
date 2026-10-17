package in.dexter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.ledger.support.LedgerIT;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** GET /v1/calendar and GET /v1/summary (MASTER_PLAN P08), IST days. */
class InsightIT extends LedgerIT {

  void add(UUID a, String at, long paise, String category, String method, String merchant) {
    Map<String, Object> m = expense(at, paise, category, method);
    m.put("merchant", merchant);
    assertThat(create(a, m).status()).isEqualTo(201);
  }

  @Test
  void calendarHasEveryDayOfTheMonthWithSpendPerRoot() {
    UUID a = UUID.randomUUID();
    add(a, "2026-09-01T04:00:00Z", 10_000, "food.dining", "upi", "Qzx Diner");
    add(a, "2026-09-01T05:00:00Z", 5_000, "travel.cab", "card", "Wkv Rides");
    add(a, "2026-09-30T19:00:00Z", 7_000, "food.dining", "upi", "Qzx Diner"); // 1 Oct in IST
    Res r = get("/v1/calendar?month=2026-09", a);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode c = r.json();
    assertThat(c.get("month").asString()).isEqualTo("2026-09");
    assertThat(c.get("days").size()).isEqualTo(30);
    assertThat(c.get("spentPaise").asLong()).isEqualTo(15_000);
    assertThat(c.get("txnCount").asInt()).isEqualTo(2);
    JsonNode d1 = c.get("days").get(0);
    assertThat(d1.get("day").asString()).isEqualTo("2026-09-01");
    assertThat(d1.get("byRoot").get("food").asLong()).isEqualTo(10_000);
    assertThat(d1.get("byRoot").get("travel").asLong()).isEqualTo(5_000);
    assertThat(d1.get("upcoming").size()).as("no liability source before P11/P13 (A-49)").isZero();
    assertThat(c.get("days").get(29).get("spentPaise").asLong()).isZero();
    assertThat(get("/v1/calendar?month=2026-10", a).json().get("spentPaise").asLong()).isEqualTo(7_000);
    assertThat(get("/v1/calendar?month=2026-9", a).code()).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void summaryGroupsByCategoryDayMerchantAndPaymentMethod() {
    UUID a = UUID.randomUUID();
    add(a, "2026-08-01T04:00:00Z", 10_000, "food.dining", "upi", "Qzx Diner");
    add(a, "2026-08-02T04:00:00Z", 30_000, "food.delivery", "card", "Qzx Diner");
    add(a, "2026-08-02T05:00:00Z", 20_000, "travel.cab", "card", "Wkv Rides");
    String q = "/v1/summary?from=2026-08-01&to=2026-08-31&groupBy=";

    JsonNode cat = get(q + "category", a).json();
    assertThat(cat.get("totalPaise").asLong()).isEqualTo(60_000);
    assertThat(cat.get("txnCount").asInt()).isEqualTo(3);
    assertThat(cat.get("groups").get(0).get("key").asString()).isEqualTo("food");
    assertThat(cat.get("groups").get(0).get("label").asString()).isEqualTo("Food");
    assertThat(cat.get("groups").get(0).get("shareBps").asInt()).isEqualTo(6_667);

    JsonNode day = get(q + "day", a).json();
    assertThat(day.get("groups").size()).isEqualTo(2);
    assertThat(day.get("groups").get(0).get("key").asString()).isEqualTo("2026-08-01");

    JsonNode merchant = get(q + "merchant", a).json();
    assertThat(merchant.get("groups").get(0).get("label").asString()).isEqualTo("Qzx Diner");
    assertThat(merchant.get("groups").get(0).get("totalPaise").asLong()).isEqualTo(40_000);
    assertThat(merchant.get("totalPaise").asLong()).isEqualTo(60_000);

    JsonNode pm = get(q + "paymentMethod", a).json();
    assertThat(pm.get("groups").get(0).get("key").asString()).isEqualTo("card");
    assertThat(pm.get("groups").get(0).get("txnCount").asInt()).isEqualTo(2);

    assertThat(get(q + "colour", a).code()).isEqualTo("VALIDATION_FAILED");
    assertThat(get("/v1/summary?from=2025-01-01&to=2026-08-31", a).code()).isEqualTo("VALIDATION_FAILED");
    assertThat(get(q + "category", UUID.randomUUID()).json().get("totalPaise").asLong()).isZero();
  }
}
