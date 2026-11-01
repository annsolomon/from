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
 * {@code GET /v1/recommendations/wallet} over HTTP (MASTER_PLAN P15).
 *
 * <p>The catalog projection is global, so every test in the suite shares it (ADR-0020 §15) and the wallet endpoint
 * has no subset filter to scope a read with. So each test here spends on category roots <em>only its own cards
 * earn on</em>: another test's card can join a wallet but can never out-earn these on these roots, which makes the
 * assertions independent of what else the suite published and of the order it ran in.
 */
class WalletIT extends RecoIT {
  @Autowired
  ProjectionService projections;

  static String today() {
    return LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
  }

  /** A card that earns only on {@code root}, so a wallet has a reason to carry more than one. */
  UUID cardOn(String root, int rateBps, Long capPaise) {
    UUID id = UUID.randomUUID();
    projections.onCatalogEvent(Events.cardPublished(id, "wallet-" + id, 1, 1,
        Events.snapshot(id, "wallet-" + id, "Wallet Card", 0, null,
            List.of(Events.rule(UUID.randomUUID(), root, "cashback", rateBps, 0L, capPaise,
                capPaise == null ? null : "month", List.of(), 0)), null)));
    return id;
  }

  void spend(UUID account, long paise, String path) {
    projections.onExpenseEvent(Events.created(account, UUID.randomUUID(), Events.state(paise, today(), path)));
  }

  /** The seat of one card in one wallet size, or null. */
  static JsonNode seat(JsonNode wallet, UUID card) {
    return wallet.get("cards").valueStream()
        .filter(c -> c.get("cardProductId").asString().equals(card.toString())).findFirst().orElse(null);
  }

  static JsonNode size(JsonNode body, int n) {
    return body.get("bySize").valueStream().filter(w -> w.get("size").asInt() == n).findFirst().orElseThrow();
  }

  @Test
  void anAnonymousRequestIs401() {
    Res r = send("GET", "/v1/recommendations/wallet", null, null);
    assertThat(r.status()).isEqualTo(401);
  }

  @Test
  void twoComplementaryCardsBeatEitherAlone() {
    UUID a = UUID.randomUUID();
    UUID foodCard = cardOn("wfood", 500, null);
    UUID travelCard = cardOn("wtravel", 500, null);
    spend(a, 1_000_000L, "wfood.dining");
    spend(a, 1_000_000L, "wtravel.air");

    Res r = get("/v1/recommendations/wallet", a);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    JsonNode body = r.json();
    assertThat(body.get("window").asString()).isEqualTo("3m");
    assertThat(body.get("monthlySpendPaise").asLong()).isEqualTo(2_000_000L);
    assertThat(body.get("bySize").size()).as("one, two and three cards").isPositive();

    JsonNode two = size(body, 2);
    assertThat(seat(two, foodCard)).as("the food card is in the two-card wallet").isNotNull();
    assertThat(seat(two, travelCard)).as("and so is the travel card").isNotNull();
    assertThat(seat(two, foodCard).get("categoryRoots").valueStream().map(JsonNode::asString).toList())
        .containsExactly("wfood");
    assertThat(seat(two, travelCard).get("categoryRoots").valueStream().map(JsonNode::asString).toList())
        .containsExactly("wtravel");
    assertThat(two.get("annualValuePaise").asLong())
        .as("5 % of both roots for a year; no other card in the suite earns on wfood/wtravel")
        .isEqualTo(1_200_000L);
    assertThat(two.get("routing").get("wtravel").asString()).isEqualTo(travelCard.toString());
    assertThat(two.get("marginalValuePaise").asLong()).as("the second card adds the second root").isPositive();
    assertThat(two.get("exact").asBoolean()).isTrue();
    assertThat(two.get("routing").get("wfood").asString()).isEqualTo(foodCard.toString());
  }

  @Test
  void aSeatSaysWhatItCarriesAndWhatItCosts() {
    UUID a = UUID.randomUUID();
    UUID card = cardOn("wgift", 400, null);
    spend(a, 800_000L, "wgift.donations");

    JsonNode one = size(get("/v1/recommendations/wallet?size=1", a).json(), 1);
    JsonNode mine = seat(one, card);
    assertThat(mine).isNotNull();
    assertThat(mine.get("annualRewardPaise").asLong()).as("4 % of Rs 8,000 a month").isEqualTo(384_000L);
    assertThat(mine.get("expectedFeePaise").asLong()).as("a free card costs nothing").isZero();
    assertThat(mine.get("reasons").size()).isPositive();
    assertThat(mine.get("reasons").valueStream().map(JsonNode::asString).toList())
        .anyMatch(s -> s.contains("wgift"));
    assertThat(one.get("marginalValuePaise").asLong()).as("the first card has nothing to be marginal to").isZero();
  }

  @Test
  void sizeIsCappedAndValidated() {
    UUID a = UUID.randomUUID();
    cardOn("wsize", 500, null);
    spend(a, 1_000_000L, "wsize.one");

    assertThat(get("/v1/recommendations/wallet?size=1", a).json().get("bySize").size()).isEqualTo(1);
    assertThat(get("/v1/recommendations/wallet?size=0", a).status()).isEqualTo(400);
    assertThat(get("/v1/recommendations/wallet?size=4", a).status()).isEqualTo(400);
  }

  @Test
  void anAccountWithNoSpendGetsAnEmptyAnswerNotAnError() {
    UUID a = UUID.randomUUID();
    cardOn("wnone", 500, null);
    Res r = get("/v1/recommendations/wallet", a);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.json().get("bySize").size()).as("nothing to optimize without spend").isZero();
    assertThat(r.json().get("bestSize").asInt()).isZero();
  }

  @Test
  void anAdminTokenIsRefused() {
    UUID a = UUID.randomUUID();
    Res r = send("GET", "/v1/recommendations/wallet", null, null,
        "Authorization", "Bearer " + IDP.user(a).audience("reco").scope("admin").sign());
    assertThat(r.status()).isEqualTo(403);
    assertThat(r.code()).isEqualTo("USER_TOKEN_REQUIRED");
  }
}
