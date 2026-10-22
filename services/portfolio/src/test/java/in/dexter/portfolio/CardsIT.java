package in.dexter.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.portfolio.support.PortfolioIT;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** /v1/cards (MASTER_PLAN P11): add (product from the catalog projection), read, If-Match patch, soft remove. */
class CardsIT extends PortfolioIT {

  @Test
  void addReadPatchRemove() {
    UUID a = UUID.randomUUID();
    Map<String, Object> body = card(POINTS_CARD, "4321", 31);
    body.put("creditLimitPaise", 25_000_000);
    body.put("openedOn", "2024-03-15");
    String key = UUID.randomUUID().toString();
    Res created = send("POST", "/v1/cards", JSON.writeValueAsString(body), "application/json",
        auth(a, "Idempotency-Key", key));
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    JsonNode c = created.json();
    String id = c.get("id").asString();
    assertThat(created.header("Location")).isEqualTo("/v1/cards/" + id);
    assertThat(created.header("ETag")).isEqualTo("\"0\"");
    assertThat(c.get("nickname").asString()).as("default: the product name without MOCK_").isEqualTo("Test Points Card");
    assertThat(c.get("network").asString()).as("from the product").isEqualTo("visa");
    assertThat(c.get("productSlug").asString()).isEqualTo("test-points-card");
    assertThat(c.get("currentCycle").get("periodEnd").asString()).endsWith(
        String.valueOf(java.time.YearMonth.from(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")))
            .atEndOfMonth().getDayOfMonth()));
    Res replay = send("POST", "/v1/cards", JSON.writeValueAsString(body), "application/json",
        auth(a, "Idempotency-Key", key));
    assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(post("/v1/cards", a, body).code()).as("same product + last4").isEqualTo("CARD_EXISTS");
    assertThat(post("/v1/cards", a, card(UUID.randomUUID(), "1111", 5)).json().get("errors").get(0).get("code")
        .asString()).isEqualTo("UNKNOWN_CARD_PRODUCT");
    assertThat(post("/v1/cards", a, card(RETIRED_CARD, "1111", 5)).json().get("errors").get(0).get("code")
        .asString()).isEqualTo("PRODUCT_RETIRED");
    Res bad = post("/v1/cards", a, card(POINTS_CARD, "12a4", 29));
    assertThat(bad.json().get("errors")).extracting(e -> e.get("field").asString()).contains("last4", "statementDay");
    assertThat(outboxRows(a, "portfolio.card.added")).isEqualTo(1);

    assertThat(get("/v1/cards/" + id, a).header("ETag")).isEqualTo("\"0\"");
    assertThat(get("/v1/cards/" + id, UUID.randomUUID()).code()).as("another tenant").isEqualTo("NOT_FOUND");
    assertThat(patch("/v1/cards/" + id, a, "{\"nickname\":\"Travel\"}", null).status()).isEqualTo(428);
    assertThat(patch("/v1/cards/" + id, a, "{\"nickname\":\"Travel\"}", "\"5\"").code()).isEqualTo("VERSION_CONFLICT");
    assertThat(patch("/v1/cards/" + id, a, "{\"last4\":\"9999\"}", "\"0\"").code()).isEqualTo("VALIDATION_FAILED");
    Res p = patch("/v1/cards/" + id, a, "{\"nickname\":\"Travel\",\"statementDay\":12,\"creditLimitPaise\":null}",
        "\"0\"");
    assertThat(p.status()).as(p.toString()).isEqualTo(200);
    assertThat(p.header("ETag")).isEqualTo("\"1\"");
    assertThat(p.json().get("statementDay").asInt()).isEqualTo(12);
    assertThat(p.json().get("creditLimitPaise").isNull()).isTrue();
    assertThat(patch("/v1/cards/" + id, a, "{\"nickname\":\"Travel\"}", "\"1\"").header("ETag"))
        .as("no change, no new version").isEqualTo("\"1\"");
    assertThat(outboxRows(a, "portfolio.card.updated")).isEqualTo(1);

    assertThat(delete("/v1/cards/" + id, a).status()).isEqualTo(204);
    assertThat(delete("/v1/cards/" + id, a).status()).as("idempotent").isEqualTo(204);
    assertThat(get("/v1/cards/" + id, a).code()).isEqualTo("NOT_FOUND");
    assertThat(outboxRows(a, "portfolio.card.removed")).isEqualTo(1);
    assertThat(post("/v1/cards", a, body).status()).as("re-adding a removed card").isEqualTo(201);
  }

  @Test
  void listsNewestFirstAndRefusesAdminTokens() {
    UUID a = UUID.randomUUID();
    UUID first = newCard(a, POINTS_CARD);
    UUID second = newCard(a, CASHBACK_CARD);
    JsonNode page = get("/v1/cards?limit=1", a).json();
    assertThat(page.get("data").get(0).get("id").asString()).isEqualTo(second.toString());
    String cursor = page.get("page").get("nextCursor").asString();
    JsonNode next = get("/v1/cards?limit=1&cursor=" + java.net.URLEncoder.encode(cursor,
        java.nio.charset.StandardCharsets.UTF_8), a).json();
    assertThat(next.get("data").get(0).get("id").asString()).isEqualTo(first.toString());
    String admin = IDP.admin(a, "SUPPORT").scope("admin").audience("portfolio").sign();
    assertThat(send("GET", "/v1/cards", null, null, "Authorization", "Bearer " + admin).code())
        .isEqualTo("USER_TOKEN_REQUIRED");
    assertThat(send("GET", "/v1/cards", null, null).status()).isEqualTo(401);
  }
}
