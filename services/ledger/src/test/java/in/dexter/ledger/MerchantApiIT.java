package in.dexter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.ledger.support.LabelledMerchants;
import in.dexter.ledger.support.LedgerIT;
import in.dexter.ledger.support.SeedMerchants;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** /v1/merchants autocomplete + resolve and /v1/rules (MASTER_PLAN P08). */
class MerchantApiIT extends LedgerIT {
  static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MerchantApiIT.class);

  @BeforeAll
  static void merchants() {
    SeedMerchants.ensureLoaded(ADMIN);
  }

  @Test
  void autocompleteRanksPrefixMatchesFirstAndCapsAtEight() {
    UUID a = UUID.randomUUID();
    Res r = get("/v1/merchants/autocomplete?q=swi", a);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode data = r.json().get("data");
    assertThat(data.size()).isBetween(1, 8);
    assertThat(data.get(0).get("merchant").asString()).startsWith("Swiggy");
    assertThat(data.get(0).get("categoryPath").asString()).isNotBlank();
    Res wide = get("/v1/merchants/autocomplete?q=an", a);
    assertThat(wide.json().get("data").size()).isEqualTo(8);
    Res typo = get("/v1/merchants/autocomplete?q=zomaot", a);
    assertThat(typo.json().get("data").get(0).get("merchant").asString()).isEqualTo("Zomato");
    assertThat(get("/v1/merchants/autocomplete?q=s", a).code()).isEqualTo("VALIDATION_FAILED");
    assertThat(send("GET", "/v1/merchants/autocomplete?q=swi", null, null).status()).isEqualTo(401);
  }

  /**
   * MASTER_PLAN P08: autocomplete within a 50 ms budget on the medium seed's merchant table (the same 357 merchants
   * and 635 aliases in every seed size). Measured over HTTP for 200 distinct cold queries (no cache hits), p95.
   */
  @Test
  void autocompleteP95IsWithinFiftyMilliseconds() {
    UUID a = UUID.randomUUID();
    Set<String> queries = new LinkedHashSet<>();
    for (LabelledMerchants.Merchant m : LabelledMerchants.merchants()) {
      String n = m.name().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9 ]", "");
      for (int len = 2; len <= Math.min(5, n.length()); len++) {
        String q = n.substring(0, len).strip();
        if (q.length() >= 2) {
          queries.add(q);
        }
      }
    }
    List<String> qs = new ArrayList<>(queries);
    Collections.shuffle(qs, new java.util.Random(3));
    for (String q : qs.subList(0, 20)) { // warm-up (JIT, pools)
      get("/v1/merchants/autocomplete?q=" + enc(q + "x"), a);
    }
    List<Long> ms = new ArrayList<>();
    for (String q : qs.subList(20, 220)) {
      long t0 = System.nanoTime();
      Res r = get("/v1/merchants/autocomplete?q=" + enc(q), a);
      ms.add((System.nanoTime() - t0) / 1_000_000);
      assertThat(r.status()).isEqualTo(200);
    }
    Collections.sort(ms);
    long p95 = ms.get((int) Math.ceil(ms.size() * 0.95) - 1);
    LOG.info("AUTOCOMPLETE_LATENCY n={} p50={}ms p95={}ms max={}ms", ms.size(), ms.get(ms.size() / 2), p95,
        ms.getLast());
    assertThat(p95).as("autocomplete p95 (ms)").isLessThanOrEqualTo(50);
  }

  @Test
  void resolveShowsTheMerchantCategoryConfidenceAndCandidates() {
    UUID a = UUID.randomUUID();
    Res r = post("/v1/merchants/resolve", a, Map.of("merchant", "UPI/123412341234/ZOMATO/zomato12@ybl"));
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode res = r.json().get("resolution");
    assertThat(res.get("merchant").asString()).isEqualTo("Zomato");
    assertThat(res.get("categoryPath").asString()).isEqualTo("food.delivery");
    assertThat(res.get("confidence").asInt()).isBetween(900, 1000);
    assertThat(res.get("method").asString()).isEqualTo("exact");
    assertThat(r.json().get("normalized").asString()).isEqualTo("zomato");

    Res unknown = post("/v1/merchants/resolve", a, Map.of("merchant", "Ganesh Tea Stall"));
    assertThat(unknown.json().get("resolution").get("merchantId").isNull()).isTrue();
    assertThat(unknown.json().get("resolution").get("categoryPath").asString()).isEqualTo("general");
    assertThat(unknown.json().get("resolution").get("confidence").asInt()).isZero();
  }

  @Test
  void rulesOverrideTheCategoryForTheirAccountOnly() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    Res bad = post("/v1/rules", a, Map.of("matchType", "contains", "pattern", "tea stall", "categoryPath", "nope.x"));
    assertThat(bad.code()).isEqualTo("VALIDATION_FAILED");
    Res created = post("/v1/rules", a, Map.of("matchType", "contains", "pattern", "Tea Stall",
        "categoryPath", "food.cafe", "priority", 10));
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    assertThat(created.json().get("pattern").asString()).isEqualTo("tea stall");
    UUID swiggy = SeedMerchants.id("swiggy");
    assertThat(post("/v1/rules", a, Map.of("matchType", "merchant_id", "pattern", swiggy.toString(),
        "categoryPath", "food.dining")).status()).isEqualTo(201);

    JsonNode ra = post("/v1/merchants/resolve", a, Map.of("merchant", "Ganesh Tea Stall")).json().get("resolution");
    assertThat(ra.get("categoryPath").asString()).isEqualTo("food.cafe");
    assertThat(ra.get("categorySource").asString()).isEqualTo("rule");
    assertThat(ra.get("confidence").asInt()).isEqualTo(1000);
    JsonNode rs = post("/v1/merchants/resolve", a, Map.of("merchant", "SWIGGY BANGALORE")).json().get("resolution");
    assertThat(rs.get("categoryPath").asString()).isEqualTo("food.dining");
    assertThat(rs.get("merchant").asString()).isEqualTo("Swiggy");
    JsonNode rb = post("/v1/merchants/resolve", b, Map.of("merchant", "Ganesh Tea Stall")).json().get("resolution");
    assertThat(rb.get("categoryPath").asString()).as("rules are per account").isEqualTo("general");

    // a new expense without a category follows the rule
    Map<String, Object> body = expense("2026-10-01T10:00:00Z", 2_000, null, "cash");
    body.put("merchant", "Ganesh Tea Stall");
    body.remove("categoryPath");
    JsonNode e = create(a, body).json();
    assertThat(e.get("categoryPath").asString()).isEqualTo("food.cafe");
    assertThat(e.get("categorySource").asString()).isEqualTo("rule");

    JsonNode list = get("/v1/rules", a).json().get("data");
    assertThat(list.size()).isEqualTo(2);
    assertThat(list.get(0).get("priority").asInt()).isEqualTo(10);
    assertThat(get("/v1/rules", b).json().get("data").size()).isZero();
    UUID id = UUID.fromString(list.get(0).get("id").asString());
    assertThat(delete("/v1/rules/" + id, b).status()).as("another account's rule is invisible").isEqualTo(404);
    assertThat(delete("/v1/rules/" + id, a).status()).isEqualTo(204);
    assertThat(get("/v1/rules", a).json().get("data").size()).isEqualTo(1);
  }

  @Test
  void createResolvesTheMerchantWhenNoCategoryIsGiven() {
    UUID a = UUID.randomUUID();
    Map<String, Object> body = expense("2026-10-02T10:00:00Z", 45_000, null, "card");
    body.remove("categoryPath");
    body.put("merchant", "POS 123456 UBER");
    Res r = create(a, body);
    assertThat(r.status()).as(r.toString()).isEqualTo(201);
    JsonNode e = r.json();
    assertThat(e.get("merchant").asString()).isEqualTo("Uber");
    assertThat(e.get("merchantId").asString()).isEqualTo(SeedMerchants.id("uber").toString());
    assertThat(e.get("categoryPath").asString()).isEqualTo("travel.cab");
    assertThat(e.get("categorySource").asString()).isEqualTo("resolver");
    assertThat(e.get("resolutionConfidence").asInt()).isGreaterThanOrEqualTo(900);
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
