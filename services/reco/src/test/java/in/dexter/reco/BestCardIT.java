package in.dexter.reco;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.reco.app.ProjectionService;
import in.dexter.reco.support.Events;
import in.dexter.reco.support.RecoIT;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /v1/recommendations/best-card} over HTTP (MASTER_PLAN P15), including the plan's named test: the
 * answer must change once a card's cap for the period is used up.
 *
 * <p>Every figure here comes the long way round — a ledger event with a {@code userCardId} on it, projected into
 * {@code card_period_spend}, then read back as cap headroom (A-86) — because the point is that reco needs no
 * fan-out to answer this. Only the cards the account holds are candidates, so unlike the wallet this endpoint is
 * naturally scoped and does not need made-up category roots to stay independent of the rest of the suite.
 */
class BestCardIT extends RecoIT {
  @Autowired
  ProjectionService projections;

  static String today() {
    return LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
  }

  /** A published card with one rule, optionally capped. */
  UUID card(String root, int rateBps, Long capPaise) {
    UUID id = UUID.randomUUID();
    projections.onCatalogEvent(Events.cardPublished(id, "best-" + id, 1, 1,
        Events.snapshot(id, "best-" + id, "Best Card", 0, null,
            List.of(Events.rule(UUID.randomUUID(), root, "cashback", rateBps, 0L, capPaise,
                capPaise == null ? null : "month", List.of(), 0)), null)));
    return id;
  }

  /** The account holds that product, through portfolio's own event. */
  UUID hold(UUID account, UUID product) {
    UUID userCardId = UUID.randomUUID();
    projections.onCardEvent(Events.cardAdded(account, userCardId, product, 1));
    return userCardId;
  }

  /** An expense put on that card, which is what moves the cap headroom. */
  void spendOn(UUID account, UUID userCardId, long paise, String path) {
    projections.onExpenseEvent(Events.created(account, UUID.randomUUID(),
        Events.onCard(paise, today(), path, userCardId, null)));
  }

  static JsonNode cardOf(JsonNode body, UUID userCardId) {
    return body.get("cards").valueStream()
        .filter(c -> c.get("userCardId").asString().equals(userCardId.toString())).findFirst().orElseThrow();
  }

  String query(long amountPaise, String category) {
    return "/v1/recommendations/best-card?amountPaise=" + amountPaise + "&category=" + category;
  }

  @Test
  void anAnonymousRequestIs401() {
    assertThat(send("GET", query(100_000L, "food.dining"), null, null).status()).isEqualTo(401);
  }

  // ------------------------------------------------------------------------------- the plan's named test

  @Test
  void anExhaustedCapChangesTheAnswer() {
    UUID a = UUID.randomUUID();
    UUID richProduct = card("food", 500, 50_000L);      // 5 %, capped at Rs 500 a month
    UUID plainProduct = card("food", 200, null);        // 2 %, uncapped
    UUID rich = hold(a, richProduct);
    UUID plain = hold(a, plainProduct);

    Res fresh = get(query(500_000L, "food.dining"), a);
    assertThat(fresh.status()).as(fresh.toString()).isEqualTo(200);
    JsonNode body = fresh.json();
    assertThat(body.get("bestUserCardId").asString()).as("with headroom, 5 % wins").isEqualTo(rich.toString());
    assertThat(cardOf(body, rich).get("rewardPaise").asLong()).as("5 % of Rs 5,000").isEqualTo(25_000L);
    assertThat(cardOf(body, rich).get("headroomPaise").asLong()).as("the whole cap").isEqualTo(50_000L);
    assertThat(cardOf(body, rich).get("capExhausted").asBoolean()).isFalse();
    assertThat(body.get("amountPaise").asLong()).isEqualTo(500_000L);
    assertThat(body.get("period").asString()).isEqualTo(LocalDate.parse(today()).withDayOfMonth(1).toString());

    // Rs 20,000 of food spend on the capped card this month uses its whole Rs 500 cap
    spendOn(a, rich, 2_000_000L, "food.dining");
    JsonNode after = get(query(500_000L, "food.dining"), a).json();
    assertThat(after.get("bestUserCardId").asString()).as("with the cap used, 2 % wins")
        .isEqualTo(plain.toString());
    assertThat(cardOf(after, rich).get("rewardPaise").asLong()).isZero();
    assertThat(cardOf(after, rich).get("capExhausted").asBoolean()).isTrue();
    assertThat(cardOf(after, rich).get("headroomPaise").asLong()).isZero();
    assertThat(cardOf(after, rich).get("periodSpendPaise").asLong())
        .as("the ledger event moved the per-card spend (A-86)").isEqualTo(2_000_000L);
    assertThat(cardOf(after, rich).get("reasons").valueStream().map(JsonNode::asString).toList())
        .anyMatch(s -> s.contains("cap on this card is already used"));
    assertThat(cardOf(after, plain).get("rewardPaise").asLong()).isEqualTo(10_000L);
  }

  @Test
  void anExpenseWithNoCardDoesNotTouchAnyCardSHeadroom() {
    UUID a = UUID.randomUUID();
    UUID product = card("food", 500, 50_000L);
    UUID uc = hold(a, product);
    // paid by UPI: the ledger event carries no userCardId, so no card's period spend moves
    projections.onExpenseEvent(Events.created(a, UUID.randomUUID(),
        Events.state(2_000_000L, today(), "food.dining")));

    JsonNode body = get(query(500_000L, "food.dining"), a).json();
    assertThat(cardOf(body, uc).get("periodSpendPaise").asLong()).isZero();
    assertThat(cardOf(body, uc).get("headroomPaise").asLong()).as("the cap is untouched").isEqualTo(50_000L);
  }

  @Test
  void anOfferOnTheHeldCardIsAddedAndExplained() {
    UUID a = UUID.randomUUID();
    UUID product = card("food", 100, null);
    UUID uc = hold(a, product);
    UUID offerId = UUID.randomUUID();
    projections.onOfferEvent(Events.offerChanged(offerId, product, null, "food", "pct", 1000, null, 20_000L,
        0L, "2026-01-01T00:00:00Z", "2099-01-01T00:00:00Z", false, "active", 1));

    JsonNode body = get(query(100_000L, "food.dining"), a).json();
    JsonNode mine = cardOf(body, uc);
    assertThat(mine.get("rewardPaise").asLong()).as("1 % of Rs 1,000").isEqualTo(1_000L);
    assertThat(mine.get("offerPaise").asLong()).as("plus 10 %, under the Rs 200 ceiling").isEqualTo(10_000L);
    assertThat(mine.get("totalPaise").asLong()).isEqualTo(11_000L);
    assertThat(mine.get("offers").size()).isEqualTo(1);
    assertThat(mine.get("offers").get(0).get("offerId").asString()).isEqualTo(offerId.toString());

    // withdrawn: the row stays but stops applying
    projections.onOfferEvent(Events.offerChanged(offerId, product, null, "food", "pct", 1000, null, 20_000L,
        0L, "2026-01-01T00:00:00Z", "2099-01-01T00:00:00Z", false, "withdrawn", 2));
    JsonNode gone = cardOf(get(query(100_000L, "food.dining"), a).json(), uc);
    assertThat(gone.get("offerPaise").asLong()).isZero();
    assertThat(gone.get("offers").size()).isZero();
    assertThat(ADMIN.sql("SELECT status FROM reco.offers_proj WHERE offer_id = :id")
        .param("id", offerId).query(String.class).single())
        .as("withdrawn is kept, not deleted, so a late event cannot resurrect it").isEqualTo("withdrawn");
  }

  @Test
  void anAccountWithNoHeldCardsGetsAnEmptyAnswer() {
    UUID a = UUID.randomUUID();
    card("food", 500, null);
    Res r = get(query(100_000L, "food.dining"), a);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.json().get("cards").size()).isZero();
    assertThat(r.json().get("bestUserCardId").isNull()).isTrue();
  }

  @Test
  void theRequestIsValidated() {
    UUID a = UUID.randomUUID();
    assertThat(get(query(0L, "food.dining"), a).code()).isEqualTo("AMOUNT_REQUIRED");
    assertThat(get(query(-5L, "food.dining"), a).code()).isEqualTo("AMOUNT_REQUIRED");
    Res neither = get("/v1/recommendations/best-card?amountPaise=100000", a);
    assertThat(neither.status()).isEqualTo(400);
    assertThat(neither.code()).isEqualTo("MERCHANT_OR_CATEGORY_REQUIRED");
    assertThat(get("/v1/recommendations/best-card?amountPaise=100000&merchantId=not-a-uuid", a).status())
        .isEqualTo(400);
    assertThat(send("GET", "/v1/recommendations/best-card?category=food", null, null, auth(a)).status())
        .as("amountPaise is required").isEqualTo(400);
  }

  @Test
  void aMerchantQueryPicksTheMerchantRule() {
    UUID a = UUID.randomUUID();
    UUID merchant = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    projections.onCatalogEvent(Events.cardPublished(id, "best-m-" + id, 1, 1,
        Events.snapshot(id, "best-m-" + id, "Merchant Card", 0, null,
            List.of(Events.rule(UUID.randomUUID(), "food", "cashback", 200, 0L, null, null, List.of(), 0),
                Events.merchantRule(UUID.randomUUID(), merchant, "cashback", 900)), null)));
    UUID uc = hold(a, id);

    JsonNode body = get("/v1/recommendations/best-card?amountPaise=100000&category=food.dining&merchantId="
        + merchant, a).json();
    assertThat(cardOf(body, uc).get("rateBps").asInt()).as("the merchant rule wins over the category rule")
        .isEqualTo(900);
    assertThat(cardOf(body, uc).get("rewardPaise").asLong()).isEqualTo(9_000L);

    JsonNode elsewhere = get(query(100_000L, "food.dining"), a).json();
    assertThat(cardOf(elsewhere, uc).get("rateBps").asInt()).as("elsewhere the category rule applies")
        .isEqualTo(200);
  }
}
