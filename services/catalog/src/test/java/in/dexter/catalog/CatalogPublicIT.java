package in.dexter.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.catalog.support.CatalogIT;
import in.dexter.catalog.support.Cards;
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

/** The public catalog (MASTER_PLAN P09): no token, published only, keyset by name, filters, search, potential. */
class CatalogPublicIT extends CatalogIT {

  @Test
  void listPagesByNameWithFiltersAndNeedsNoToken() {
    String issuer = newIssuer();
    String tag = Cards.suffix();
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      Map<String, Object> d = Cards.doc("pub-" + tag + "-" + i, "Zz Pub " + tag + " " + i, issuer,
          i % 2 == 0 ? "rupay" : "amex", 10_000L * i);
      if (i == 3) {
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(Cards.rule(null, 100));
        rules.add(Cards.rule("food", 300));
        rules.add(Cards.rule("food.delivery", 1000));
        d.put("rules", rules);
      }
      UUID id = newCard(d);
      ids.add(id);
      if (i != 6) {
        assertThat(publish(id).status()).isEqualTo(200);
      }
    }
    Set<String> seen = new HashSet<>();
    List<String> names = new ArrayList<>();
    String cursor = null;
    do {
      Res r = anon("GET", "/v1/catalog/cards?issuer=" + issuer + "&limit=2" + (cursor == null ? "" : "&cursor="
          + enc(cursor)));
      assertThat(r.status()).as(r.toString()).isEqualTo(200);
      r.json().get("data").forEach(c -> {
        assertThat(seen.add(c.get("id").asString())).isTrue();
        names.add(c.get("name").asString());
      });
      JsonNode next = r.json().get("page").get("nextCursor");
      cursor = next == null || next.isNull() ? null : next.asString();
    } while (cursor != null);
    assertThat(seen).as("6 published, the draft is hidden").hasSize(6).doesNotContain(ids.get(6).toString());
    assertThat(names).isSorted();

    assertThat(anon("GET", "/v1/catalog/cards?issuer=" + issuer + "&network=amex").json().get("data").size())
        .isEqualTo(3);
    assertThat(anon("GET", "/v1/catalog/cards?issuer=" + issuer + "&maxAnnualFee=20000").json().get("data").size())
        .isEqualTo(3);
    // the most specific rule decides: food.delivery 10 % on card 3; food.dining falls back to food 3 %
    JsonNode deliv = anon("GET", "/v1/catalog/cards?issuer=" + issuer + "&category=food.delivery&minRateBps=800")
        .json().get("data");
    assertThat(deliv.size()).isEqualTo(1);
    assertThat(deliv.get(0).get("id").asString()).isEqualTo(ids.get(3).toString());
    assertThat(anon("GET", "/v1/catalog/cards?issuer=" + issuer + "&category=food.dining&minRateBps=300")
        .json().get("data").size()).isEqualTo(1);
    assertThat(anon("GET", "/v1/catalog/cards?issuer=" + issuer + "&category=fuel.petrol").json().get("data").size())
        .as("the base rule excludes fuel; no fuel rule").isZero();
    Res first = anon("GET", "/v1/catalog/cards?issuer=" + issuer + "&limit=2");
    String c = first.json().get("page").get("nextCursor").asString();
    assertThat(anon("GET", "/v1/catalog/cards?network=amex&limit=2&cursor=" + enc(c)).code())
        .isEqualTo("CURSOR_FILTER_MISMATCH");
    assertThat(anon("GET", "/v1/catalog/cards?network=paypal").code()).isEqualTo("VALIDATION_FAILED");
    assertThat(anon("GET", "/v1/catalog/cards?minRateBps=100").code()).as("minRateBps needs a category")
        .isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void detailSearchAndPotential() {
    String tag = Cards.suffix();
    Map<String, Object> doc = Cards.doc("detail-" + tag, "Quokka Rewards " + tag, newIssuer(), "mastercard", 0);
    List<Map<String, Object>> rules = new ArrayList<>();
    rules.add(Cards.rule(null, 100));
    rules.add(Cards.rule("food.delivery", 9_000)); // far above any other test card: always in the top 10
    doc.put("rules", rules);
    UUID id = newCard(doc);
    assertThat(publish(id).status()).isEqualTo(200);
    Res d = anon("GET", "/v1/catalog/cards/detail-" + tag);
    assertThat(d.status()).isEqualTo(200);
    assertThat(d.json().get("id").asString()).isEqualTo(id.toString());
    assertThat(d.json().get("version").asInt()).isEqualTo(1);
    assertThat(anon("GET", "/v1/catalog/cards/no-such-card").code()).isEqualTo("NOT_FOUND");

    JsonNode hits = anon("GET", "/v1/catalog/cards/search?q=quokka").json().get("data");
    assertThat(hits.size()).isGreaterThanOrEqualTo(1);
    assertThat(hits.get(0).get("slug").asString()).startsWith("detail-");
    assertThat(anon("GET", "/v1/catalog/cards/search?q=qokka%20rewads").json().get("data").size())
        .as("trigram tolerates typos").isGreaterThanOrEqualTo(1);
    assertThat(anon("GET", "/v1/catalog/cards/search?q=q").code()).isEqualTo("VALIDATION_FAILED");

    JsonNode pot = anon("GET", "/v1/catalog/categories/potential?category=food.delivery.pizza").json();
    assertThat(pot.get("category").asString()).isEqualTo("food.delivery.pizza");
    JsonNode cards = pot.get("cards");
    assertThat(cards.size()).isBetween(1, 10);
    for (int i = 1; i < cards.size(); i++) {
      assertThat(cards.get(i).get("rateBps").asInt()).isLessThanOrEqualTo(cards.get(i - 1).get("rateBps").asInt());
    }
    assertThat(cards.get(0).get("rateBps").asInt()).isGreaterThanOrEqualTo(9_000);
    assertThat(cards.toString()).contains(id.toString());
    assertThat(anon("GET", "/v1/catalog/categories/potential?category=Bad!").code()).isEqualTo("VALIDATION_FAILED");
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
