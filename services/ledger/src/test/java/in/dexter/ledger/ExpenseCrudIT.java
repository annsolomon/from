package in.dexter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.ledger.support.LedgerIT;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * MASTER_PLAN P07 endpoints end to end: create (Idempotency-Key, Location, ETag), get, merge-patch with If-Match
 * (412/428), soft delete + restore, daily totals in step, one outbox row per write with before/after state,
 * tenant isolation, validation, client-ref de-duplication, categories.
 */
class ExpenseCrudIT extends LedgerIT {

  @Test
  void createGetPatchDeleteRestore() {
    UUID a = UUID.randomUUID();
    Res created = create(a, expense("2026-10-16T19:00:00Z", 45_000, "food.dining", "card"));
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    JsonNode e = created.json();
    String id = e.get("id").asString();
    assertThat(created.header("Location")).isEqualTo("/v1/expenses/" + id);
    assertThat(created.header("ETag")).isEqualTo("\"0\"");
    assertThat(e.get("categorySource").asString()).isEqualTo("user");
    assertThat(e.get("merchant").asString()).isEqualTo("Test Merchant");
    assertThat(dailyTotal(a, "2026-10-17", "food")).as("19:00Z is the 17th in IST").isEqualTo(45_000);

    Res got = get("/v1/expenses/" + id, a);
    assertThat(got.status()).isEqualTo(200);
    assertThat(got.header("ETag")).isEqualTo("\"0\"");

    assertThat(patch("/v1/expenses/" + id, a, "{\"amountPaise\":50000}", null).status()).as("If-Match required")
        .isEqualTo(428);
    assertThat(patch("/v1/expenses/" + id, a, "{\"amountPaise\":50000}", "\"7\"").status()).isEqualTo(412);
    Res p = patch("/v1/expenses/" + id, a,
        "{\"amountPaise\":50000,\"categoryPath\":\"travel.cab\",\"occurredAt\":\"2026-10-15T06:00:00Z\","
            + "\"note\":\"airport\",\"tags\":[\"Work\",\"work\",\"trip\"]}", "\"0\"");
    assertThat(p.status()).as(p.toString()).isEqualTo(200);
    assertThat(p.header("ETag")).isEqualTo("\"1\"");
    assertThat(p.json().get("tags").toString()).isEqualTo("[\"work\",\"trip\"]");
    assertThat(dailyTotal(a, "2026-10-17", "food")).as("old bucket emptied and removed").isNull();
    assertThat(dailyTotal(a, "2026-10-15", "travel")).isEqualTo(50_000);
    assertThat(patch("/v1/expenses/" + id, a, "{\"amountPaise\":50000}", "\"1\"").header("ETag"))
        .as("no-op patch keeps the version").isEqualTo("\"1\"");
    assertThat(patch("/v1/expenses/" + id, a, "{\"userCardId\":\"" + UUID.randomUUID() + "\",\"paymentMethod\":\"cash\"}",
        "\"1\"").code()).isEqualTo("VALIDATION_FAILED");
    assertThat(patch("/v1/expenses/" + id, a, "{\"currency\":\"USD\"}", "\"1\"").code()).isEqualTo("UNKNOWN_FIELD");

    assertThat(delete("/v1/expenses/" + id, a).status()).isEqualTo(204);
    assertThat(delete("/v1/expenses/" + id, a).status()).as("idempotent").isEqualTo(204);
    assertThat(get("/v1/expenses/" + id, a).status()).isEqualTo(404);
    assertThat(dailyTotal(a, "2026-10-15", "travel")).isNull();
    Res restored = post("/v1/expenses/" + id + ":restore", a, null);
    assertThat(restored.status()).as(restored.toString()).isEqualTo(200);
    assertThat(restored.header("ETag")).isEqualTo("\"3\"");
    assertThat(dailyTotal(a, "2026-10-15", "travel")).isEqualTo(50_000);
    assertThat(post("/v1/expenses/" + id + ":archive", a, null).status()).isEqualTo(404);

    for (String t : List.of("created", "updated", "deleted", "restored")) {
      assertThat(outboxRows(a, "ledger.expense." + t)).as(t).isEqualTo(1);
    }
    JsonNode updated = JSON.readTree(ADMIN.sql("SELECT payload::text FROM ledger.outbox WHERE message_key = :k"
        + " AND event_type = 'ledger.expense.updated'").param("k", a.toString()).query(String.class).single());
    assertThat(updated.get("data").get("before").get("day").asString()).isEqualTo("2026-10-17");
    assertThat(updated.get("data").get("before").get("amountPaise").asLong()).isEqualTo(45_000);
    assertThat(updated.get("data").get("after").get("categoryPath").asString()).isEqualTo("travel.cab");
    assertThat(updated.get("aggregateVersion").asLong()).isEqualTo(1);
  }

  @Test
  void anotherAccountSeesNothing() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    String id = create(a, expense("2026-10-10T10:00:00Z", 1_000, "food", "upi")).json().get("id").asString();
    assertThat(get("/v1/expenses/" + id, b).status()).isEqualTo(404);
    assertThat(patch("/v1/expenses/" + id, b, "{\"amountPaise\":1}", "\"0\"").status()).isEqualTo(404);
    assertThat(delete("/v1/expenses/" + id, b).status()).isEqualTo(404);
    assertThat(get("/v1/expenses", b).json().get("data").size()).isZero();
    assertThat(get("/v1/expenses", a).json().get("data").size()).isEqualTo(1);
  }

  @Test
  void validationAndConflicts() {
    UUID a = UUID.randomUUID();
    Res bad = create(a, Map.of("amountPaise", 0, "paymentMethod", "barter", "categoryPath", "Food"));
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.body()).contains("occurredAt").contains("amountPaise").contains("paymentMethod")
        .contains("categoryPath");
    Res unknown = create(a, expense("2026-10-10T10:00:00Z", 100, "food.unicorns", "cash"));
    assertThat(unknown.body()).contains("UNKNOWN_CATEGORY");
    Res cardOnUpi = create(a, Map.of("occurredAt", "2026-10-10T10:00:00Z", "amountPaise", 100, "paymentMethod", "upi",
        "userCardId", UUID.randomUUID().toString()));
    assertThat(cardOnUpi.body()).contains("NOT_A_CARD_PAYMENT");
    Map<String, Object> withRef = expense("2026-10-10T10:00:00Z", 100, "general", "cash");
    withRef.put("clientRef", "imp-1");
    assertThat(create(a, withRef).status()).isEqualTo(201);
    Res dup = create(a, withRef);
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.code()).isEqualTo("CLIENT_REF_TAKEN");
    assertThat(create(UUID.randomUUID(), withRef).status()).as("client refs are per account").isEqualTo(201);
    assertThat(post("/v1/expenses", a, expense("2026-10-10T10:00:00Z", 100, "general", "cash")).code())
        .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    assertThat(send("GET", "/v1/expenses", null, null).status()).isEqualTo(401);
    assertThat(get("/v1/expenses?from=2026-13-01", a).code()).isEqualTo("VALIDATION_FAILED");
    assertThat(get("/v1/expenses?category=Food", a).code()).isEqualTo("VALIDATION_FAILED");
    String admin = IDP.admin(UUID.randomUUID(), "SUPPORT").audience("ledger").scope("admin").sign();
    assertThat(send("GET", "/v1/expenses", null, null, "Authorization", "Bearer " + admin).code())
        .isEqualTo("USER_TOKEN_REQUIRED");
  }

  @Test
  void filtersNarrowTheList() {
    UUID a = UUID.randomUUID();
    UUID card = UUID.randomUUID();
    Map<String, Object> onCard = expense("2026-09-01T10:00:00Z", 900, "food.dining", "card");
    onCard.put("userCardId", card.toString());
    create(a, onCard);
    create(a, expense("2026-09-02T10:00:00Z", 2_000, "food.groceries", "upi"));
    create(a, expense("2026-09-03T10:00:00Z", 30_000, "travel.air", "card"));
    assertThat(ids(a, "")).hasSize(3);
    assertThat(ids(a, "&category=food")).hasSize(2);
    assertThat(ids(a, "&category=food.dining")).hasSize(1);
    assertThat(ids(a, "&paymentMethod=card")).hasSize(2);
    assertThat(ids(a, "&cardId=" + card)).hasSize(1);
    assertThat(ids(a, "&minPaise=1000&maxPaise=5000")).hasSize(1);
    assertThat(ids(a, "&from=2026-09-02&to=2026-09-02")).hasSize(1);
    assertThat(ids(a, "&from=2026-09-02")).hasSize(2);
    Res totals = get("/v1/expenses/daily-totals?from=2026-09-01&to=2026-09-30", a);
    assertThat(totals.status()).isEqualTo(200);
    assertThat(totals.json().get("data").size()).isEqualTo(3);
  }

  @Test
  void categoriesSystemTreeAndCustomSubtrees() {
    UUID a = UUID.randomUUID();
    Res list = get("/v1/categories", a);
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.json().get("data").size()).isGreaterThanOrEqualTo(48);
    Res c = post("/v1/categories", a, Map.of("parentPath", "food", "name", "Street food", "icon", "cart"));
    assertThat(c.status()).as(c.toString()).isEqualTo(201);
    assertThat(c.json().get("path").asString()).isEqualTo("food.street_food");
    assertThat(post("/v1/categories", a, Map.of("parentPath", "food", "name", "Street food")).code())
        .isEqualTo("CATEGORY_EXISTS");
    assertThat(post("/v1/categories", a, Map.of("parentPath", "food", "name", "Dining")).code())
        .as("cannot shadow a system path").isEqualTo("CATEGORY_EXISTS");
    assertThat(post("/v1/categories", a, Map.of("parentPath", "nowhere", "name", "X")).body())
        .contains("UNKNOWN_CATEGORY");
    assertThat(create(a, expense("2026-10-10T10:00:00Z", 100, "food.street_food", "cash")).status()).isEqualTo(201);
    UUID b = UUID.randomUUID();
    assertThat(create(b, expense("2026-10-10T10:00:00Z", 100, "food.street_food", "cash")).body())
        .as("custom categories are private").contains("UNKNOWN_CATEGORY");
    assertThat(get("/v1/categories", b).body()).doesNotContain("street_food");
  }

  List<String> ids(UUID a, String q) {
    Res r = get("/v1/expenses?limit=50" + q, a);
    assertThat(r.status()).as(q + " " + r).isEqualTo(200);
    List<String> out = new java.util.ArrayList<>();
    r.json().get("data").forEach(n -> out.add(n.get("id").asString()));
    return out;
  }

  static Long dailyTotal(UUID a, String day, String root) {
    return ADMIN.sql("SELECT total_paise FROM ledger.expense_daily_totals WHERE account_id = :a AND day = CAST(:d AS date)"
            + " AND category_root = CAST(:r AS ltree)")
        .param("a", a).param("d", day).param("r", root).query(Long.class).optional().orElse(null);
  }
}
