package in.dexter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.ledger.support.LedgerIT;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * POST /v1/expenses:bulk (MASTER_PLAN P08): per-item results with partial failure, clientRef as the natural key
 * (across calls and inside one call), Idempotency-Key replay, daily totals and one created event per new row.
 */
class BulkExpensesIT extends LedgerIT {

  Res bulk(UUID account, List<Map<String, Object>> items, String key) {
    return post("/v1/expenses:bulk", account, Map.of("items", items), "Idempotency-Key", key);
  }

  Map<String, Object> item(String day, long paise, String ref) {
    Map<String, Object> m = expense(day + "T06:00:00Z", paise, "food.dining", "upi");
    if (ref != null) {
      m.put("clientRef", ref);
    }
    return m;
  }

  long dayTotal(UUID a, String day) {
    return ADMIN.sql("SELECT coalesce(sum(total_paise), 0) FROM ledger.expense_daily_totals WHERE account_id = :a"
        + " AND day = CAST(:d AS date)").param("a", a).param("d", day).query(Long.class).single();
  }

  @Test
  void partialFailureCreatesTheValidItemsAndReportsEachOne() {
    UUID a = UUID.randomUUID();
    Map<String, Object> badAmount = item("2026-09-10", 0, null);
    Map<String, Object> badCategory = item("2026-09-10", 500, null);
    badCategory.put("categoryPath", "nope.nothing");
    List<Map<String, Object>> items = List.of(item("2026-09-10", 1_000, "r-1"), badAmount, item("2026-09-10", 2_000,
        "r-1"), badCategory, item("2026-09-11", 3_000, null));
    String key = UUID.randomUUID().toString();
    Res r = bulk(a, items, key);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode b = r.json();
    assertThat(b.get("created").asInt()).isEqualTo(2);
    assertThat(b.get("duplicates").asInt()).isEqualTo(1);
    assertThat(b.get("invalid").asInt()).isEqualTo(2);
    JsonNode res = b.get("results");
    assertThat(res.get(0).get("status").asString()).isEqualTo("created");
    assertThat(res.get(1).get("status").asString()).isEqualTo("invalid");
    assertThat(res.get(1).get("errors").get(0).get("field").asString()).isEqualTo("items[1].amountPaise");
    assertThat(res.get(2).get("status").asString()).as("same clientRef earlier in the request").isEqualTo("duplicate");
    assertThat(res.get(2).get("id").asString()).isEqualTo(res.get(0).get("id").asString());
    assertThat(res.get(3).get("errors").get(0).get("code").asString()).isEqualTo("UNKNOWN_CATEGORY");
    assertThat(res.get(4).get("status").asString()).isEqualTo("created");

    assertThat(dayTotal(a, "2026-09-10")).isEqualTo(1_000);
    assertThat(dayTotal(a, "2026-09-11")).isEqualTo(3_000);
    assertThat(outboxRows(a, "ledger.expense.created")).isEqualTo(2);

    // same key → the same answer, nothing new
    Res replay = bulk(a, items, key);
    assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.body()).isEqualTo(r.body());
    // new key, same clientRef → duplicate of the existing row
    Res again = bulk(a, List.of(item("2026-09-10", 1_000, "r-1")), UUID.randomUUID().toString());
    assertThat(again.json().get("results").get(0).get("status").asString()).isEqualTo("duplicate");
    assertThat(again.json().get("results").get(0).get("id").asString()).isEqualTo(res.get(0).get("id").asString());
    assertThat(outboxRows(a, "ledger.expense.created")).isEqualTo(2);
  }

  @Test
  void fiveHundredItemsAtMostAndAKeyIsRequired() {
    UUID a = UUID.randomUUID();
    List<Map<String, Object>> many = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      many.add(item("2026-09-12", 100 + i, "many-" + i));
    }
    Res ok = bulk(a, many, UUID.randomUUID().toString());
    assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
    assertThat(ok.json().get("created").asInt()).isEqualTo(500);
    assertThat(outboxRows(a, "ledger.expense.created")).isEqualTo(500);
    many.add(item("2026-09-12", 1, null));
    assertThat(bulk(a, many, UUID.randomUUID().toString()).code()).isEqualTo("VALIDATION_FAILED");
    assertThat(post("/v1/expenses:bulk", a, Map.of("items", List.of(item("2026-09-12", 1, null)))).code())
        .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    assertThat(bulk(a, List.of(), UUID.randomUUID().toString()).code()).isEqualTo("VALIDATION_FAILED");
  }
}
