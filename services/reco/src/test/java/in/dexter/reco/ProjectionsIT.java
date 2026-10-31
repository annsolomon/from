package in.dexter.reco;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.reco.app.ProjectionService;
import in.dexter.reco.support.Events;
import in.dexter.reco.support.RecoIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The held-card and catalog projections (MASTER_PLAN P14), including the plan's named test: projections must
 * ignore stale versions.
 */
class ProjectionsIT extends RecoIT {
  @Autowired
  ProjectionService projections;

  static long heldCount(UUID account) {
    return ADMIN.sql("SELECT count(1) FROM reco.held_cards_proj WHERE account_id = :a")
        .param("a", account).query(Long.class).single();
  }

  static long projectedVersion(UUID account, UUID userCardId) {
    return ADMIN.sql("SELECT projected_version FROM reco.held_cards_proj WHERE account_id = :a"
            + " AND user_card_id = :uc").param("a", account).param("uc", userCardId)
        .query(Long.class).optional().orElse(-1L);
  }

  static long ruleCount(UUID cardProductId) {
    return ADMIN.sql("SELECT count(1) FROM reco.catalog_rules_proj WHERE card_product_id = :id")
        .param("id", cardProductId).query(Long.class).single();
  }

  static String cardStatus(UUID cardProductId) {
    return ADMIN.sql("SELECT status FROM reco.catalog_cards_proj WHERE card_product_id = :id")
        .param("id", cardProductId).query(String.class).optional().orElse(null);
  }

  @Test
  void aHeldCardIsProjectedAndRemovedAgain() {
    UUID a = UUID.randomUUID();
    UUID userCard = UUID.randomUUID();
    UUID product = UUID.randomUUID();
    assertThat(projections.onCardEvent(Events.cardAdded(a, userCard, product, 1)))
        .isEqualTo(ProjectionService.Outcome.APPLIED);
    assertThat(heldCount(a)).isEqualTo(1);
    assertThat(state(a).get("dirty")).as("a new card makes the account stale").isEqualTo(true);

    assertThat(projections.onCardEvent(Events.cardRemoved(a, userCard, product, 2)))
        .isEqualTo(ProjectionService.Outcome.APPLIED);
    assertThat(heldCount(a)).as("portfolio keeps the history; reco only answers which cards are held").isZero();
  }

  @Test
  void aStaleCardEventIsIgnored() {
    UUID a = UUID.randomUUID();
    UUID userCard = UUID.randomUUID();
    UUID product = UUID.randomUUID();
    projections.onCardEvent(Events.cardAdded(a, userCard, product, 5));
    assertThat(projectedVersion(a, userCard)).isEqualTo(5);

    // an out-of-order delivery of version 3 must not overwrite version 5
    assertThat(projections.onCardEvent(Events.card("portfolio.card.updated", a, userCard, UUID.randomUUID(),
        "active", 3))).isEqualTo(ProjectionService.Outcome.STALE);
    assertThat(projectedVersion(a, userCard)).isEqualTo(5);
  }

  @Test
  void aReplayedRemoveIsNotAFailure() {
    UUID a = UUID.randomUUID();
    UUID userCard = UUID.randomUUID();
    projections.onCardEvent(Events.cardAdded(a, userCard, UUID.randomUUID(), 1));
    var remove = Events.cardRemoved(a, userCard, UUID.randomUUID(), 2);
    assertThat(projections.onCardEvent(remove)).isEqualTo(ProjectionService.Outcome.APPLIED);
    assertThat(projections.onCardEvent(remove)).as("the inbox catches the redelivery")
        .isEqualTo(ProjectionService.Outcome.DUPLICATE);
    assertThat(heldCount(a)).isZero();
  }

  @Test
  void aPublishedCardBringsItsRulesAndANewVersionReplacesThem() {
    UUID cardId = UUID.randomUUID();
    UUID rule1 = UUID.randomUUID();
    UUID rule2 = UUID.randomUUID();
    assertThat(projections.onCatalogEvent(Events.cardPublished(cardId, "proj-card", 1, 1,
        Events.snapshot(cardId, "proj-card", "Proj Card", 100_000L, 30_000_000L,
            List.of(Events.rule(rule1, "food", "cashback", 500, 0L, 50_000L, "month", List.of(), 0),
                Events.rule(rule2, null, "cashback", 100, 0L, null, null, List.of(), 0)),
            Events.milestone(12_000_000L, "year", 600_000L)))))
        .isEqualTo(ProjectionService.Outcome.APPLIED);
    assertThat(ruleCount(cardId)).isEqualTo(2);
    assertThat(cardStatus(cardId)).isEqualTo("published");
    assertThat(ADMIN.sql("SELECT fee_waiver_spend_paise FROM reco.catalog_cards_proj WHERE card_product_id = :id")
        .param("id", cardId).query(Long.class).single()).isEqualTo(30_000_000L);
    assertThat(ADMIN.sql("SELECT milestone::text FROM reco.catalog_cards_proj WHERE card_product_id = :id")
        .param("id", cardId).query(String.class).single()).contains("spendThresholdPaise");

    // version 2 has one rule with a new id: the old rules go, they are not merged
    UUID rule3 = UUID.randomUUID();
    assertThat(projections.onCatalogEvent(Events.cardPublished(cardId, "proj-card", 2, 2,
        Events.snapshot(cardId, "proj-card", "Proj Card", 0, null,
            List.of(Events.rule(rule3, "travel", "points", 300, 250L, null, null, List.of(), 0)), null))))
        .isEqualTo(ProjectionService.Outcome.APPLIED);
    assertThat(ruleCount(cardId)).as("a version brings new rule ids, so the set is replaced").isEqualTo(1);
    assertThat(ADMIN.sql("SELECT rule_id FROM reco.catalog_rules_proj WHERE card_product_id = :id")
        .param("id", cardId).query(UUID.class).single()).isEqualTo(rule3);
    assertThat(ADMIN.sql("SELECT milestone FROM reco.catalog_cards_proj WHERE card_product_id = :id")
        .param("id", cardId).query(String.class).optional().orElse(null)).isNull();
  }

  @Test
  void aStaleCatalogEventIsIgnored() {
    UUID cardId = UUID.randomUUID();
    projections.onCatalogEvent(Events.cardPublished(cardId, "stale-card", 5, 5,
        Events.snapshot(cardId, "stale-card", "Stale Card", 0, null,
            List.of(Events.rule(UUID.randomUUID(), "food", "cashback", 500, 0L, null, null, List.of(), 0)),
            null)));
    assertThat(projections.onCatalogEvent(Events.cardPublished(cardId, "stale-card", 2, 2,
        Events.snapshot(cardId, "stale-card", "Older", 0, null, List.of(), null))))
        .isEqualTo(ProjectionService.Outcome.STALE);
    assertThat(ADMIN.sql("SELECT name FROM reco.catalog_cards_proj WHERE card_product_id = :id")
        .param("id", cardId).query(String.class).single()).isEqualTo("Stale Card");
    assertThat(ruleCount(cardId)).as("a stale event does not clear the rules either").isEqualTo(1);
  }

  @Test
  void aRetiredCardLeavesTheCandidateSet() {
    UUID cardId = UUID.randomUUID();
    var snapshot = Events.snapshot(cardId, "retire-card", "Retire Card", 0, null,
        List.of(Events.rule(UUID.randomUUID(), "food", "cashback", 500, 0L, null, null, List.of(), 0)), null);
    projections.onCatalogEvent(Events.cardPublished(cardId, "retire-card", 1, 1, snapshot));
    assertThat(cardStatus(cardId)).isEqualTo("published");
    projections.onCatalogEvent(Events.cardRetired(cardId, "retire-card", 2, 2, snapshot));
    assertThat(cardStatus(cardId)).isEqualTo("retired");
  }

  @Test
  void anotherEventTypeIsIgnored() {
    assertThat(projections.onCatalogEvent(Events.cardAdded(UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), 1))).isEqualTo(ProjectionService.Outcome.IGNORED);
    assertThat(projections.onCardEvent(Events.cardPublished(UUID.randomUUID(), "x", 1, 1,
        Events.snapshot(UUID.randomUUID(), "x", "X", 0, null, List.of(), null))))
        .isEqualTo(ProjectionService.Outcome.IGNORED);
  }
}
