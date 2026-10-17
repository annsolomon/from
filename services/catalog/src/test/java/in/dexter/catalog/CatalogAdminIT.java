package in.dexter.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.catalog.support.CatalogIT;
import in.dexter.catalog.support.Cards;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Catalog administration (MASTER_PLAN P09): drafts are invisible until published; a publish freezes an immutable
 * version, closes the previous version and its rules at the same instant, updates the public face and writes a
 * {@code catalog.card.published} event; an unchanged republish is a no-op; permissions are enforced.
 */
class CatalogAdminIT extends CatalogIT {

  long outbox(UUID card, String type) {
    return ADMIN.sql("SELECT count(1) FROM catalog.outbox WHERE message_key = :k AND event_type = :t")
        .param("k", card.toString()).param("t", type).query(Long.class).single();
  }

  @Test
  void draftPublishEditRepublishRetire() {
    String issuer = newIssuer();
    String slug = "test-card-" + Cards.suffix();
    Map<String, Object> doc = Cards.doc(slug, "Test Card " + slug, issuer, "visa", 50_000);
    Res created = as(editor(), "POST", "/v1/admin/catalog/cards", doc);
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    assertThat(created.header("ETag")).isEqualTo("\"0\"");
    UUID id = UUID.fromString(created.json().get("cardId").asString());
    assertThat(created.json().get("status").asString()).isEqualTo("draft");

    assertThat(anon("GET", "/v1/catalog/cards/" + slug).status()).as("a draft is not public").isEqualTo(404);
    assertThat(as(editor(), "POST", "/v1/admin/catalog/cards", doc).code()).isEqualTo("SLUG_TAKEN");

    Res p1 = publish(id);
    assertThat(p1.status()).as(p1.toString()).isEqualTo(200);
    assertThat(p1.json().get("version").asInt()).isEqualTo(1);
    assertThat(p1.json().get("changed").asBoolean()).isTrue();
    Res pub = anon("GET", "/v1/catalog/cards/" + slug);
    assertThat(pub.status()).isEqualTo(200);
    assertThat(pub.header("ETag")).isEqualTo("\"1\"");
    JsonNode card = pub.json();
    assertThat(card.get("issuerName").asString()).isEqualTo("Test Issuer " + issuer);
    assertThat(card.get("rules").size()).isEqualTo(2);
    assertThat(card.get("milestone").get("benefitType").asString()).isEqualTo("voucher");
    assertThat(outbox(id, "catalog.card.published")).isEqualTo(1);

    Res again = publish(id);
    assertThat(again.json().get("changed").asBoolean()).as("unchanged draft: no new version").isFalse();
    assertThat(again.json().get("version").asInt()).isEqualTo(1);
    assertThat(outbox(id, "catalog.card.published")).isEqualTo(1);

    // edit the draft (If-Match), republish → v2; v1 closed, its rules closed at the same instant
    Res draft = as(editor(), "GET", "/v1/admin/catalog/cards/" + id + "/draft", null);
    assertThat(draft.json().get("unpublishedChanges").asBoolean()).isFalse();
    Map<String, Object> edited = Cards.doc(slug, "Test Card " + slug + " Plus", issuer, "visa", 75_000);
    assertThat(as(editor(), "PUT", "/v1/admin/catalog/cards/" + id + "/draft", edited).status())
        .as("If-Match required").isEqualTo(428);
    assertThat(as(editor(), "PUT", "/v1/admin/catalog/cards/" + id + "/draft", edited, "If-Match", "\"9\"").code())
        .isEqualTo("VERSION_CONFLICT");
    Res saved = as(editor(), "PUT", "/v1/admin/catalog/cards/" + id + "/draft", edited, "If-Match",
        draft.header("ETag"));
    assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
    assertThat(saved.json().get("unpublishedChanges").asBoolean()).isTrue();
    assertThat(anon("GET", "/v1/catalog/cards/" + slug).json().get("annualFeePaise").asLong())
        .as("the public sees v1 until the publish").isEqualTo(50_000);
    Map<String, Object> renamed = Cards.doc("other-" + slug, "x", issuer, "visa", 1);
    assertThat(as(editor(), "PUT", "/v1/admin/catalog/cards/" + id + "/draft", renamed, "If-Match",
        saved.header("ETag")).code()).as("slug is fixed").isEqualTo("VALIDATION_FAILED");

    Res p2 = publish(id);
    assertThat(p2.json().get("version").asInt()).isEqualTo(2);
    JsonNode v2 = anon("GET", "/v1/catalog/cards/" + slug).json();
    assertThat(v2.get("annualFeePaise").asLong()).isEqualTo(75_000);
    assertThat(v2.get("version").asInt()).isEqualTo(2);
    JsonNode versions = as(editor(), "GET", "/v1/admin/catalog/cards/" + id + "/versions", null).json().get("data");
    assertThat(versions.size()).isEqualTo(2);
    assertThat(versions.get(0).get("effectiveTo").isNull()).isTrue();
    assertThat(versions.get(1).get("effectiveTo").asString()).isEqualTo(versions.get(0).get("effectiveFrom").asString());
    List<Map<String, Object>> ranges = ADMIN.sql("SELECT version, lower(valid) AS lo, upper(valid) AS hi"
        + " FROM catalog.reward_rules WHERE card_product_id = :c ORDER BY version, id").param("c", id).query().listOfRows();
    assertThat(ranges).hasSize(4);
    assertThat(ranges.stream().filter(r -> ((Number) r.get("version")).intValue() == 1)
        .allMatch(r -> r.get("hi") != null)).as("v1 rules closed").isTrue();
    assertThat(ranges.stream().filter(r -> ((Number) r.get("version")).intValue() == 2)
        .allMatch(r -> r.get("hi") == null)).as("v2 rules open").isTrue();
    assertThat(outbox(id, "catalog.card.published")).isEqualTo(2);

    Res retired = as(editor(), "POST", "/v1/admin/catalog/cards/" + id + ":retire", null);
    assertThat(retired.status()).as(retired.toString()).isEqualTo(200);
    assertThat(anon("GET", "/v1/catalog/cards/" + slug).status()).isEqualTo(404);
    assertThat(publish(id).code()).isEqualTo("CARD_RETIRED");
    assertThat(outbox(id, "catalog.card.retired")).isEqualTo(1);
  }

  @Test
  void permissionsAndTokenKinds() {
    UUID someone = UUID.randomUUID();
    String path = "/v1/admin/catalog/cards";
    assertThat(anon("GET", path).status()).isEqualTo(401);
    assertThat(get(path, someone).status()).as("user token").isEqualTo(403);
    String readOnly = editorToken(UUID.randomUUID(), "catalog:read");
    assertThat(as(readOnly, "GET", path, null).status()).isEqualTo(200);
    assertThat(as(readOnly, "POST", path, Cards.doc("x-" + Cards.suffix(), "x", "NOPE", "visa", 0)).status())
        .as("catalog:write missing").isEqualTo(403);
    String writer = editorToken(UUID.randomUUID(), "catalog:read", "catalog:write");
    UUID id = newCard(Cards.doc("perm-" + Cards.suffix(), "Perm Card", newIssuer(), "rupay", 0));
    assertThat(as(writer, "POST", path + "/" + id + ":publish", null).status()).as("catalog:publish missing")
        .isEqualTo(403);
    // a token with the permission but without a catalog role cannot pass RLS: the service refuses it first
    String noRole = IDP.admin(UUID.randomUUID(), "SUPPORT").scope("admin").audience("catalog")
        .permissions("catalog:read").sign();
    assertThat(as(noRole, "GET", path, null).status()).isEqualTo(403);
  }

  @Test
  void validationNamesTheFields() {
    Map<String, Object> bad = Cards.doc("Bad Slug!", "", "NOPE", "paypal", -1);
    Res r = as(editor(), "POST", "/v1/admin/catalog/cards", bad);
    assertThat(r.code()).isEqualTo("VALIDATION_FAILED");
    assertThat(r.body()).contains("\"slug\"").contains("\"name\"").contains("\"network\"")
        .contains("\"annualFeePaise\"");
    Map<String, Object> unknownIssuer = Cards.doc("ok-" + Cards.suffix(), "Ok", "NO_SUCH", "visa", 0);
    assertThat(as(editor(), "POST", "/v1/admin/catalog/cards", unknownIssuer).body()).contains("UNKNOWN_ISSUER");
    Map<String, Object> dup = Cards.doc("dup-" + Cards.suffix(), "Dup", newIssuer(), "visa", 0);
    dup.put("rules", List.of(Cards.rule("food", 100), Cards.rule("food", 200)));
    assertThat(as(editor(), "POST", "/v1/admin/catalog/cards", dup).body()).contains("DUPLICATE_SCOPE");
  }

  @Test
  void offersAreCreatedListedAndWithdrawn() {
    String issuer = newIssuer();
    UUID merchant = UUID.randomUUID();
    Map<String, Object> offer = new java.util.LinkedHashMap<>();
    offer.put("issuerCode", issuer);
    offer.put("merchantId", merchant.toString());
    offer.put("categoryPath", "food.delivery");
    offer.put("title", "10% off on food delivery");
    offer.put("kind", "pct");
    offer.put("valueBps", 1000);
    offer.put("maxDiscountPaise", 15_000);
    offer.put("minSpendPaise", 50_000);
    offer.put("validFrom", "2026-01-01T00:00:00Z");
    offer.put("stackable", true);
    Res c = as(editor(), "POST", "/v1/admin/catalog/offers", offer);
    assertThat(c.status()).as(c.toString()).isEqualTo(201);
    UUID id = UUID.fromString(c.json().get("offerId").asString());
    JsonNode byMerchant = anon("GET", "/v1/catalog/offers?merchantId=" + merchant).json().get("data");
    assertThat(byMerchant.size()).isEqualTo(1);
    assertThat(byMerchant.get(0).get("valueBps").asInt()).isEqualTo(1000);
    assertThat(anon("GET", "/v1/catalog/offers?category=food.delivery.pizza").body()).contains(id.toString());
    assertThat(anon("GET", "/v1/catalog/offers?category=travel").body()).doesNotContain(id.toString());
    Map<String, Object> wrong = new java.util.LinkedHashMap<>(offer);
    wrong.put("kind", "flat");
    assertThat(as(editor(), "POST", "/v1/admin/catalog/offers", wrong).code()).isEqualTo("VALIDATION_FAILED");

    Res w = as(editor(), "POST", "/v1/admin/catalog/offers/" + id + ":withdraw", null);
    assertThat(w.json().get("status").asString()).isEqualTo("withdrawn");
    assertThat(anon("GET", "/v1/catalog/offers?merchantId=" + merchant).json().get("data").size()).isZero();
    assertThat(as(editor(), "POST", "/v1/admin/catalog/offers/" + id + ":withdraw", null).code())
        .isEqualTo("OFFER_WITHDRAWN");
    assertThat(ADMIN.sql("SELECT count(1) FROM catalog.outbox WHERE message_key = :k AND event_type = :t")
        .param("k", id.toString()).param("t", "catalog.offer.changed").query(Long.class).single()).isEqualTo(2);
  }
}
