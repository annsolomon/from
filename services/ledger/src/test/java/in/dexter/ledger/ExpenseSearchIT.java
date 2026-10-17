package in.dexter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.ledger.support.LedgerIT;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** GET /v1/expenses/search (MASTER_PLAN P08): text match, date bound, keyset paging, tenant isolation. */
class ExpenseSearchIT extends LedgerIT {

  Map<String, Object> item(String at, String merchant, String note) {
    Map<String, Object> m = expense(at, 10_000, "general", "upi");
    m.put("merchant", merchant);
    if (note != null) {
      m.put("note", note);
    }
    return m;
  }

  @Test
  void findsMerchantAndNoteTextNewestFirstWithinTheWindow() {
    UUID a = UUID.randomUUID();
    assertThat(create(a, item("2026-09-01T10:00:00Z", "Corner Bakery Qz42", null)).status()).isEqualTo(201);
    assertThat(create(a, item("2026-09-05T10:00:00Z", "Grocer", "bakery order for the office")).status())
        .isEqualTo(201);
    assertThat(create(a, item("2026-09-03T10:00:00Z", "Petrol bunk", null)).status()).isEqualTo(201);
    assertThat(create(a, item("2024-01-01T10:00:00Z", "Old Bakery", null)).status()).isEqualTo(201);

    Res r = get("/v1/expenses/search?q=bakery&from=2026-01-01&to=2026-10-17", a);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode data = r.json().get("data");
    assertThat(data.size()).as("the 2024 row is outside the window").isEqualTo(2);
    assertThat(data.get(0).get("note").asString()).contains("bakery"); // newest first
    assertThat(data.get(1).get("merchant").asString()).isEqualTo("Corner Bakery Qz42");

    Res typo = get("/v1/expenses/search?q=bakry&from=2026-01-01&to=2026-10-17", a);
    assertThat(typo.json().get("data").size()).as("word similarity on the merchant").isGreaterThanOrEqualTo(1);

    Res like = get("/v1/expenses/search?q=" + enc("100%") + "&from=2026-01-01&to=2026-10-17", a);
    assertThat(like.json().get("data").size()).as("% is literal").isZero();

    assertThat(get("/v1/expenses/search?q=bakery&from=2026-01-01&to=2026-10-17", UUID.randomUUID())
        .json().get("data").size()).as("another tenant").isZero();
    assertThat(get("/v1/expenses/search?q=b", a).code()).isEqualTo("VALIDATION_FAILED");
    assertThat(get("/v1/expenses/search?q=bakery&from=2024-01-01&to=2026-10-17", a).code())
        .as("more than 366 days").isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void pagesWithTheCursorWithoutGapsOrRepeats() {
    UUID a = UUID.randomUUID();
    for (int i = 0; i < 23; i++) {
      assertThat(create(a, item(String.format("2026-08-%02dT0%d:00:00Z", 1 + i, i % 9), "Chaiqz Spot " + i, null))
          .status()).isEqualTo(201);
    }
    Set<String> seen = new HashSet<>();
    List<String> order = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      Res r = get("/v1/expenses/search?q=chaiqz&from=2026-08-01&to=2026-08-31&limit=10"
          + (cursor == null ? "" : "&cursor=" + enc(cursor)), a);
      assertThat(r.status()).as(r.toString()).isEqualTo(200);
      r.json().get("data").forEach(e -> {
        assertThat(seen.add(e.get("id").asString())).isTrue();
        order.add(e.get("occurredAt").asString());
      });
      JsonNode next = r.json().get("page").get("nextCursor");
      cursor = next == null || next.isNull() ? null : next.asString();
      pages++;
    } while (cursor != null);
    assertThat(seen).hasSize(23);
    assertThat(pages).isEqualTo(3);
    assertThat(order).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    Res first = get("/v1/expenses/search?q=chaiqz&from=2026-08-01&to=2026-08-31&limit=10", a);
    String c = first.json().get("page").get("nextCursor").asString();
    assertThat(get("/v1/expenses/search?q=spot&from=2026-08-01&to=2026-08-31&limit=10&cursor=" + enc(c), a).code())
        .isEqualTo("CURSOR_FILTER_MISMATCH");
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
