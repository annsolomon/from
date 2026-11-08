package in.dexter.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.commerce.app.ProjectionService.Outcome;
import in.dexter.commerce.support.CommerceIT;
import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.portfolio.CardChangedV1;
import in.dexter.core.id.Ids;
import in.dexter.events.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The three projections (MASTER_PLAN 6.4): inbox dedupe, version guard, and what they change downstream. */
class ProjectionsIT extends CommerceIT {

  @Test
  void catalogCardsAreVersionGuardedAndRetirementStopsApplications() {
    UUID card = Ids.newId();
    EventEnvelope v2 = envelope(CardPublishedV1.PUBLISHED, null, card, 2, new CardPublishedV1(card, "proj-card",
        "published", 2, "2026-01-01", null, Map.of("name", "Proj Card", "issuerCode", "MOCK", "network", "visa")));
    assertThat(projections.onCatalogEvent(v2)).isEqualTo(Outcome.APPLIED);
    assertThat(projections.onCatalogEvent(v2)).as("the same event again").isEqualTo(Outcome.DUPLICATE);
    EventEnvelope v1 = envelope(CardPublishedV1.PUBLISHED, null, card, 1, new CardPublishedV1(card, "proj-card",
        "published", 1, "2026-01-01", null, Map.of("name", "Old Name")));
    assertThat(projections.onCatalogEvent(v1)).as("an older version arriving late").isEqualTo(Outcome.STALE);
    assertThat(ADMIN.sql("SELECT name FROM commerce.card_products_proj WHERE card_product_id = :c").param("c", card)
        .query(String.class).single()).isEqualTo("Proj Card");

    UUID a = UUID.randomUUID();
    String body = "{\"cardProductId\":\"" + card + "\"}";
    assertThat(send("POST", "/v1/card-applications", body, auth(a, "Idempotency-Key", UUID.randomUUID().toString()))
        .status()).isEqualTo(201);
    projections.onCatalogEvent(envelope(CardPublishedV1.RETIRED, null, card, 3, new CardPublishedV1(card,
        "proj-card", "retired", 3, "2026-01-01", "2026-11-08", Map.of("name", "Proj Card"))));
    Res after = send("POST", "/v1/card-applications", body, auth(UUID.randomUUID(), "Idempotency-Key",
        UUID.randomUUID().toString()));
    assertThat(after.body()).contains("UNKNOWN_CARD_PRODUCT");
  }

  @Test
  void aRemovedCardStopsMatching() {
    ensureCards();
    String cat = "proj" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    UUID d = deal("Card A only", cat, List.of(CARD_A), 1_000_000);
    UUID holder = UUID.randomUUID();
    UUID userCard = Ids.newId();
    projections.onCardEvent(envelope(CardChangedV1.ADDED, holder, userCard, 1, new CardChangedV1(userCard, CARD_A,
        "x", "1111", "visa", 1, "active", 1, Instant.now().toString())));
    assertThat(get("/v1/deals/" + d, holder).json().get("deal").get("cardMatch").asString()).isEqualTo("held");
    projections.onCardEvent(envelope(CardChangedV1.REMOVED, holder, userCard, 2, new CardChangedV1(userCard, CARD_A,
        "x", "1111", "visa", 1, "removed", 2, Instant.now().toString())));
    assertThat(get("/v1/deals/" + d, holder).json().get("deal").get("cardMatch").asString()).isEqualTo("other");
    assertThat(get("/v1/deals?category=" + cat, holder).json().get("data")).hasSize(1);
  }
}
