package in.dexter.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.commerce.support.CommerceIT;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The marketplace (MASTER_PLAN P17): the savings golden through the API, card-match as a ranking branch (never a
 * gate), the ranked keyset feed, public access with an optional token, and the operators' deal administration.
 * Each test uses its own category subtree so the shared database's other deals stay out of its feed.
 */
class DealsIT extends CommerceIT {

  @BeforeEach
  void cards() {
    ensureCards();
  }

  static String category() {
    return "it" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + ".online";
  }

  static List<String> ids(JsonNode page) {
    List<String> out = new ArrayList<>();
    page.get("data").forEach(d -> out.add(d.get("id").asString()));
    return out;
  }

  @Test
  void goldenSavingsThroughTheApi() {
    UUID d = deal("Golden", category(), List.of(), 7_500_000);
    Res r = get("/v1/deals/" + d, null);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    assertThat(r.json().get("spendPaise").asLong()).isEqualTo(7_500_000);
    assertThat(r.json().get("totalSavingsPaise").asLong()).isEqualTo(700_000);           // ₹75,000 -> ₹7,000
    assertThat(r.json().get("lines").get(0).get("savingPaise").asLong()).isEqualTo(500_000);
    assertThat(r.json().get("lines").get(1).get("savingPaise").asLong()).isEqualTo(200_000);
    assertThat(r.json().get("deal").get("savingsPaise").asLong()).isEqualTo(700_000);
    assertThat(r.json().get("deal").has("landingUrl")).as("the public never sees the affiliate URL").isFalse();
    assertThat(r.json().get("deal").get("clickPath").asString()).startsWith("/r/");
    Res small = get("/v1/deals/" + d + "?spendPaise=2000000", null);
    assertThat(small.json().get("totalSavingsPaise").asLong()).isEqualTo(300_000);
  }

  @Test
  void cardMatchRanksButNeverFilters() {
    String cat = category();
    UUID forA = deal("For card A", cat, List.of(CARD_A), 1_000_000);
    UUID forB = deal("For card B", cat, List.of(CARD_B), 1_000_000);
    UUID open = deal("Any card", cat, List.of(), 1_000_000);
    UUID holder = UUID.randomUUID();
    holdCard(holder, CARD_A);

    Res anon = get("/v1/deals?category=" + cat, null);
    assertThat(anon.status()).as(anon.toString()).isEqualTo(200);
    assertThat(ids(anon.json())).containsExactly(open.toString(), forA.toString(), forB.toString())
        .as("anonymous: the open deal first (cardMatch 0.5), the card deals after, in id order");
    Res mine = get("/v1/deals?category=" + cat, holder);
    assertThat(ids(mine.json())).as("the held card's deal first, the others still listed")
        .containsExactly(forA.toString(), open.toString(), forB.toString());
    JsonNode first = mine.json().get("data").get(0);
    assertThat(first.get("cardMatch").asString()).isEqualTo("held");
    assertThat(first.get("eligibleCards").get(0).get("name").asString()).isEqualTo("IT Card A");
    assertThat(mine.json().get("data").get(2).get("cardMatch").asString()).isEqualTo("other");
    assertThat(first.get("scoreBps").asInt()).isEqualTo(4_800 + 3_200);
  }

  @Test
  void sponsorshipIsTwentyPercentOfTheScore() {
    String cat = category();
    UUID plain = deal("Plain", cat, List.of(), 1_000_000);
    Res r = admin("POST", "/v1/admin/commerce/deals", dealBody("Sponsored", cat, List.of(), 1_000_000, true, 100,
        "active"));
    assertThat(r.status()).as(r.toString()).isEqualTo(201);
    UUID sponsored = UUID.fromString(r.json().get("id").asString());
    Res feed = get("/v1/deals?category=" + cat, null);
    assertThat(ids(feed.json())).containsExactly(sponsored.toString(), plain.toString());
    assertThat(feed.json().get("data").get(0).get("scoreBps").asInt()).isEqualTo(2_400 + 3_200 + 2_000);
  }

  @Test
  void theFeedPagesEveryDealOnceInRankOrder() {
    String cat = category();
    List<String> created = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      created.add(deal("Paged " + i, cat, List.of(), 100_000L * (i % 4 + 1)).toString());
    }
    List<String> seen = new ArrayList<>();
    List<Integer> scores = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      Res r = get("/v1/deals?category=" + cat + "&limit=5" + (cursor == null ? "" : "&cursor=" + enc(cursor)), null);
      assertThat(r.status()).as(r.toString()).isEqualTo(200);
      r.json().get("data").forEach(d -> {
        seen.add(d.get("id").asString());
        scores.add(d.get("scoreBps").asInt());
      });
      JsonNode next = r.json().get("page").get("nextCursor");
      cursor = next == null || next.isNull() ? null : next.asString();
      pages++;
    } while (cursor != null);
    assertThat(pages).isEqualTo(3);
    assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
    assertThat(scores).isSortedAccordingTo((x, y) -> Integer.compare(y, x));
    Res other = get("/v1/deals?category=" + category() + "&limit=5&cursor=" + enc(firstCursor(cat)), null);
    assertThat(other.status()).as("a cursor is bound to its category").isEqualTo(400);
    assertThat(get("/v1/deals?category=NOT%20AN%20LTREE", null).status()).isEqualTo(400);
  }

  String firstCursor(String cat) {
    return get("/v1/deals?category=" + cat + "&limit=5", null).json().get("page").get("nextCursor").asString();
  }

  @Test
  void theTokenIsOptionalButMustVerify() {
    String cat = category();
    deal("Any", cat, List.of(), 1_000_000);
    assertThat(get("/v1/deals?category=" + cat, null).status()).isEqualTo(200);
    Res bad = send("GET", "/v1/deals?category=" + cat, null, "Authorization", "Bearer not-a-jwt");
    assertThat(bad.status()).as(bad.toString()).isEqualTo(401);
    Res admin = send("GET", "/v1/deals?category=" + cat, null, authWith(operator()));
    assertThat(admin.status()).as("an admin token is accepted and treated as anonymous").isEqualTo(200);
    String path = admin.json().get("data").get(0).get("clickPath").asString();
    assertThat(path).startsWith("/r/");
  }

  @Test
  void draftsPausedAndUnknownAreNotListed() {
    String cat = category();
    Res draft = admin("POST", "/v1/admin/commerce/deals", dealBody("Draft", cat, List.of(), 1_000_000, false, 0,
        "draft"));
    assertThat(draft.status()).as(draft.toString()).isEqualTo(201);
    UUID live = deal("Live", cat, List.of(), 1_000_000);
    assertThat(ids(get("/v1/deals?category=" + cat, null).json())).containsExactly(live.toString());
    assertThat(get("/v1/deals/" + draft.json().get("id").asString(), null).code()).isEqualTo("DEAL_NOT_FOUND");
    assertThat(get("/v1/deals/" + UUID.randomUUID(), null).status()).isEqualTo(404);

    Res one = admin("GET", "/v1/admin/commerce/deals/" + live, null);
    assertThat(one.header("ETag")).isEqualTo("\"0\"");
    Res stale = admin("POST", "/v1/admin/commerce/deals/" + live + ":status", "{\"status\":\"paused\"}",
        "If-Match", "\"7\"");
    assertThat(stale.status()).isEqualTo(412);
    Res paused = admin("POST", "/v1/admin/commerce/deals/" + live + ":status", "{\"status\":\"paused\"}",
        "If-Match", "\"0\"");
    assertThat(paused.status()).as(paused.toString()).isEqualTo(200);
    assertThat(paused.json().get("status").asString()).isEqualTo("paused");
    assertThat(get("/v1/deals?category=" + cat, null).json().get("data")).isEmpty();
    Res ended = admin("POST", "/v1/admin/commerce/deals/" + live + ":status", "{\"status\":\"ended\"}",
        "If-Match", "\"1\"");
    assertThat(ended.json().get("status").asString()).isEqualTo("ended");
    Res back = admin("POST", "/v1/admin/commerce/deals/" + live + ":status", "{\"status\":\"active\"}",
        "If-Match", "\"2\"");
    assertThat(back.status()).as("ended is final").isEqualTo(400);
  }

  @Test
  void operatorsOnlyAndValidated() {
    String body = dealBody("X", category(), List.of(), 1_000_000, false, 0, "active");
    assertThat(send("POST", "/v1/admin/commerce/deals", body, auth(UUID.randomUUID())).status())
        .as("a user token").isEqualTo(403);
    String noWrite = operator(UUID.randomUUID(), List.of("SUPPORT"), "commerce:read", "commerce:decide");
    assertThat(send("POST", "/v1/admin/commerce/deals", body, authWith(noWrite)).status())
        .as("SUPPORT has no commerce:write").isEqualTo(403);
    Res invalid = admin("POST", "/v1/admin/commerce/deals", body.replace("https://merchant.example", "http://x")
        .replace("\"valueBps\":1000", "\"valueBps\":0"));
    assertThat(invalid.status()).as(invalid.toString()).isEqualTo(400);
    assertThat(invalid.body()).contains("landingUrl").contains("components[0]");
    deal("Listed 1", category(), List.of(), 1_000_000);
    deal("Listed 2", category(), List.of(), 1_000_000);
    Res list = admin("GET", "/v1/admin/commerce/deals?limit=2", null);
    assertThat(list.status()).as(list.toString()).isEqualTo(200);
    assertThat(list.json().get("data")).hasSize(2);
    assertThat(list.json().get("data").get(0).has("landingUrl")).isTrue();
  }
}
