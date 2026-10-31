package in.dexter.reco;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.reco.app.ProjectionService;
import in.dexter.reco.support.Events;
import in.dexter.reco.support.RecoIT;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /v1/recommendations} and {@code /explain} over HTTP (MASTER_PLAN P14), including freshness: a read
 * must never serve a result built from inputs that have since changed.
 *
 * <p>The catalog projection is global by design (ADR-0020 §9), so every card any test here publishes is a
 * candidate for every other test's account. A test that is about <em>ranking</em> therefore scores an explicit
 * subset with {@code ?cardProductIds=}; a test that is about the cached full read — which is the only path that
 * consults the state hash — keeps the full read and looks its own card up by id instead of taking the first row.
 */
class RecommendationsIT extends RecoIT {
  @Autowired
  ProjectionService projections;

  static String today() {
    return LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
  }

  /** Score only these cards, so a ranking assertion does not depend on what the rest of the class published. */
  static String only(UUID... cards) {
    return "?cardProductIds=" + Stream.of(cards).map(UUID::toString).collect(Collectors.joining(","));
  }

  /** One card's annual savings from a full read, whichever bucket it landed in. */
  static long savingsOf(JsonNode body, UUID card) {
    return Stream.concat(body.get("recommended").valueStream(), body.get("notRecommended").valueStream())
        .filter(n -> n.get("cardProductId").asString().equals(card.toString()))
        .findFirst().orElseThrow(() -> new AssertionError("card was not scored at all: " + card))
        .get("annualSavingsPaise").asLong();
  }

  /** A cashback card on `food`, with an annual fee and a waiver. */
  UUID foodCard(long feePaise, Long waiver, int rateBps, Long capPaise) {
    UUID id = UUID.randomUUID();
    projections.onCatalogEvent(Events.cardPublished(id, "food-" + id, 1, 1,
        Events.snapshot(id, "food-" + id, "Food Card", feePaise, waiver,
            List.of(Events.rule(UUID.randomUUID(), "food", "cashback", rateBps, 0L, capPaise,
                capPaise == null ? null : "month", List.of(), 0)), null)));
    return id;
  }

  UUID travelCard(int rateBps) {
    UUID id = UUID.randomUUID();
    projections.onCatalogEvent(Events.cardPublished(id, "travel-" + id, 1, 1,
        Events.snapshot(id, "travel-" + id, "Travel Card", 0, null,
            List.of(Events.rule(UUID.randomUUID(), "travel", "cashback", rateBps, 0L, null, null, List.of(), 0)),
            null)));
    return id;
  }

  void spend(UUID account, long paise, String path) {
    projections.onExpenseEvent(Events.created(account, UUID.randomUUID(), Events.state(paise, today(), path)));
  }

  @Test
  void aFoodSpenderIsRecommendedTheFoodCard() {
    UUID a = UUID.randomUUID();
    UUID food = foodCard(0, null, 500, null);
    UUID travel = travelCard(500);
    spend(a, 2_000_000L, "food.dining");

    Res r = get("/v1/recommendations" + only(food, travel), a);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode body = r.json();
    assertThat(body.get("window").asString()).isEqualTo("3m");
    assertThat(body.get("monthlySpendPaise").asLong()).isEqualTo(2_000_000L);
    assertThat(body.get("fresh").asBoolean()).isTrue();
    assertThat(body.get("savingsWeightBps").asInt()).isEqualTo(6_000);

    JsonNode recommended = body.get("recommended");
    assertThat(recommended.size()).isPositive();
    assertThat(recommended.get(0).get("cardProductId").asString()).isEqualTo(food.toString());
    assertThat(recommended.get(0).get("annualSavingsPaise").asLong())
        .as("5 % of Rs 20,000 a month, for a year").isEqualTo(1_200_000L);
    assertThat(recommended.get(0).get("reasons").size()).isPositive();
    assertThat(body.get("notRecommended")).isNotNull();
  }

  @Test
  void aHeldCardIsMarkedAsHeld() {
    UUID a = UUID.randomUUID();
    UUID food = foodCard(0, null, 500, null);
    spend(a, 2_000_000L, "food.dining");
    projections.onCardEvent(Events.cardAdded(a, UUID.randomUUID(), food, 1));

    JsonNode body = get("/v1/recommendations" + only(food), a).json();
    JsonNode card = body.get("recommended").get(0);
    assertThat(card.get("cardProductId").asString()).isEqualTo(food.toString());
    assertThat(card.get("held").asBoolean()).isTrue();
  }

  @Test
  void aReadAfterTheInputsChangedIsRecomputedNotStale() {
    UUID a = UUID.randomUUID();
    UUID card = foodCard(0, null, 500, null);
    spend(a, 1_000_000L, "food.dining");
    // the full read, because the stored result and its hash are what this test is about
    assertThat(savingsOf(get("/v1/recommendations", a).json(), card)).isEqualTo(600_000L);

    // more spend arrives, and nothing has swept: the read must still answer with the new figure
    spend(a, 1_000_000L, "food.dining");
    assertThat(savingsOf(get("/v1/recommendations", a).json(), card))
        .as("the read recomputes when the stored hash no longer matches").isEqualTo(1_200_000L);
  }

  @Test
  void aRepublishedCardChangesTheAnswerWithoutAnyFanOut() {
    UUID a = UUID.randomUUID();
    UUID card = foodCard(0, null, 300, null);
    spend(a, 1_000_000L, "food.dining");
    assertThat(savingsOf(get("/v1/recommendations", a).json(), card)).isEqualTo(360_000L);

    // catalog publishes version 2 with a better rate; no per-account dirty flag was set (A-83)
    projections.onCatalogEvent(Events.cardPublished(card, "food-" + card, 2, 2,
        Events.snapshot(card, "food-" + card, "Food Card", 0, null,
            List.of(Events.rule(UUID.randomUUID(), "food", "cashback", 600, 0L, null, null, List.of(), 0)),
            null)));
    assertThat(savingsOf(get("/v1/recommendations", a).json(), card))
        .as("the read noticed through the state hash").isEqualTo(720_000L);
  }

  @Test
  void aSubsetScoresOnlyThoseCards() {
    UUID a = UUID.randomUUID();
    UUID food = foodCard(0, null, 500, null);
    UUID travel = travelCard(500);
    spend(a, 2_000_000L, "food.dining");

    JsonNode body = get("/v1/recommendations?cardProductIds=" + travel, a).json();
    List<String> ids = body.get("recommended").valueStream().map(n -> n.get("cardProductId").asString()).toList();
    List<String> no = body.get("notRecommended").valueStream().map(n -> n.get("cardProductId").asString()).toList();
    assertThat(ids).doesNotContain(food.toString());
    assertThat(no).doesNotContain(food.toString());
    assertThat(ids.size() + no.size()).as("only the one card asked about").isEqualTo(1);
  }

  @Test
  void explainShowsTheArithmetic() {
    UUID a = UUID.randomUUID();
    UUID card = foodCard(100_000L, 30_000_000L, 500, 50_000L);
    spend(a, 2_000_000L, "food.dining");

    Res r = get("/v1/recommendations/explain/" + card, a);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode e = r.json();
    assertThat(e.get("cardProductId").asString()).isEqualTo(card.toString());
    assertThat(e.get("window").asString()).isEqualTo("3m");
    JsonNode line = e.get("lines").get(0);
    assertThat(line.get("categoryRoot").asString()).isEqualTo("food");
    assertThat(line.get("rateBps").asInt()).isEqualTo(500);
    assertThat(line.get("capBound").asBoolean()).as("Rs 500 a month caps a Rs 1,000 reward").isTrue();
    assertThat(line.get("annualCapPaise").asLong()).isEqualTo(600_000L);
    assertThat(e.get("fee").get("annualFeePaise").asLong()).isEqualTo(100_000L);
    // the waiver is a ramp, not a step (ADR-0020 §7): Rs 2,40,000 a year against a Rs 3,00,000 threshold is
    // 60 % of the way from half the threshold to the threshold, so the fee is counted at 40 % of Rs 1,000
    assertThat(e.get("fee").get("waiverProbabilityBps").asInt()).isEqualTo(6_000);
    assertThat(e.get("fee").get("expectedFeePaise").asLong()).isEqualTo(40_000L);
    assertThat(e.get("annualRewardPaise").asLong()).isEqualTo(600_000L);
    assertThat(e.get("annualSavingsPaise").asLong()).as("Rs 6,000 of reward less the Rs 400 expected fee")
        .isEqualTo(560_000L);
    assertThat(e.get("reasons").size()).isPositive();
    assertThat(e.get("rewardRateBpsByRoot").get("food").asInt()).isPositive();
  }

  @Test
  void explainForAnUnknownCardIs404() {
    UUID a = UUID.randomUUID();
    foodCard(0, null, 500, null);
    spend(a, 1_000_000L, "food.dining");
    Res r = get("/v1/recommendations/explain/" + UUID.randomUUID(), a);
    assertThat(r.status()).isEqualTo(404);
    assertThat(r.code()).isEqualTo("CARD_NOT_IN_CATALOG");
  }

  @Test
  void aColdAccountGetsAnEmptyRankingNotAnError() {
    UUID a = UUID.randomUUID();
    foodCard(100_000L, null, 500, null);
    Res r = get("/v1/recommendations", a);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.json().get("monthlySpendPaise").asLong()).isZero();
    assertThat(r.json().get("recommended").size()).isZero();
  }

  @Test
  void anotherAccountSeesItsOwnProfileOnly() {
    UUID a = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    foodCard(0, null, 500, null);
    spend(a, 2_000_000L, "food.dining");

    assertThat(get("/v1/recommendations", a).json().get("monthlySpendPaise").asLong()).isEqualTo(2_000_000L);
    assertThat(get("/v1/recommendations", other).json().get("monthlySpendPaise").asLong())
        .as("RLS: another account's spend is not visible").isZero();
  }

  @Test
  void anonymousAndAdminTokensAreRefused() {
    assertThat(send("GET", "/v1/recommendations", null, null).status()).isEqualTo(401);
    Res admin = send("GET", "/v1/recommendations", null, null, "Authorization",
        "Bearer " + IDP.admin(UUID.randomUUID(), "SUPPORT").scope("admin").audience("reco").sign());
    assertThat(admin.status()).isEqualTo(403);
    assertThat(admin.code()).isEqualTo("USER_TOKEN_REQUIRED");
  }

  @Test
  void aMalformedCardProductIdsListIsRefused() {
    UUID a = UUID.randomUUID();
    assertThat(get("/v1/recommendations?cardProductIds=not-a-uuid", a).status()).isEqualTo(400);
    String many = String.join(",", java.util.Collections.nCopies(51, UUID.randomUUID().toString()));
    assertThat(get("/v1/recommendations?cardProductIds=" + many, a).status()).isEqualTo(400);
  }
}
