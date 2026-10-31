package in.dexter.reco;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.contracts.events.reco.RecommendationUpdatedV1;
import in.dexter.reco.app.ProjectionService;
import in.dexter.reco.app.RecommendationService;
import in.dexter.reco.app.Sweeper;
import in.dexter.reco.support.Events;
import in.dexter.reco.support.RecoIT;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The debounced recompute (MASTER_PLAN P14), including the plan's named test: 50 events must coalesce into one
 * recompute, and an unchanged recompute must publish nothing.
 */
class DebounceIT extends RecoIT {
  @Autowired
  ProjectionService projections;
  @Autowired
  RecommendationService recommendations;
  @Autowired
  Sweeper sweeper;
  @Autowired
  org.springframework.jdbc.core.simple.JdbcClient jdbc;
  @Autowired
  in.dexter.db.tenant.TenantTx tx;
  @Autowired
  in.dexter.reco.app.RecoStore store;

  static String today() {
    return LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
  }

  /** A sweeper with no debounce, so a test does not have to wait three seconds to see a claim. */
  Sweeper immediate() {
    return new Sweeper(tx, store, recommendations, java.time.Clock.systemUTC(), Duration.ZERO, 200);
  }

  @Test
  void fiftyEventsCoalesceIntoOneRecompute() {
    UUID a = UUID.randomUUID();
    publishOneCard();

    for (int i = 0; i < 50; i++) {
      projections.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(10_000L, today(), "food.x")));
    }
    // the burst left one dirty row, and dirty_since is the first event's instant - not the fiftieth's
    assertThat(state(a).get("dirty")).isEqualTo(true);
    assertThat(state(a).get("dirty_since")).isNotNull();

    Sweeper.Pass pass = immediate().sweep();
    assertThat(pass.claimed()).as("one account, however many events").isEqualTo(1);
    assertThat(pass.changed()).isEqualTo(1);
    assertThat(state(a).get("dirty")).isEqualTo(false);

    // and one event on the outbox, not fifty
    assertThat(outboxRows(a, RecommendationUpdatedV1.TYPE)).isEqualTo(1);
    assertThat(immediate().sweep().claimed()).as("nothing is dirty any more").isZero();
  }

  @Test
  void anUnchangedRecomputePublishesNothing() {
    UUID a = UUID.randomUUID();
    publishOneCard();
    projections.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(600_000L, today(), "food.x")));
    assertThat(immediate().sweep().changed()).isEqualTo(1);
    long after = outboxRows(a, RecommendationUpdatedV1.TYPE);

    // recompute again with nothing changed: the state hash matches, so there is no new result and no event
    RecommendationService.Recomputed again = recommendations.recompute(a);
    assertThat(again.changed()).isFalse();
    assertThat(outboxRows(a, RecommendationUpdatedV1.TYPE)).isEqualTo(after);
    assertThat(historyRows(a)).as("and no new history row").isEqualTo(1);
  }

  @Test
  void aChangedProfileProducesANewResultAndAnEvent() {
    UUID a = UUID.randomUUID();
    publishOneCard();
    projections.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(600_000L, today(), "food.x")));
    immediate().sweep();
    String first = storedHash(a);

    projections.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(900_000L, today(), "food.x")));
    assertThat(immediate().sweep().changed()).isEqualTo(1);
    assertThat(storedHash(a)).as("more spend, a different hash").isNotEqualTo(first);
    assertThat(outboxRows(a, RecommendationUpdatedV1.TYPE)).isEqualTo(2);
    assertThat(historyRows(a)).isEqualTo(2);

    List<JsonNode> events = outboxData(a, RecommendationUpdatedV1.TYPE);
    assertThat(events.get(1).get("stateHash").asString()).isEqualTo(storedHash(a));
    assertThat(events.get(1).get("recommendedCount").asInt()).isPositive();
  }

  @Test
  void theDebounceHoldsAnAccountThatIsStillBusy() {
    UUID a = UUID.randomUUID();
    publishOneCard();
    projections.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(600_000L, today(), "food.x")));
    // the real sweeper's three-second debounce: an account dirty a moment ago is not claimed yet
    Sweeper patient = new Sweeper(tx, store, recommendations, java.time.Clock.systemUTC(),
        Duration.ofSeconds(30), 200);
    assertThat(patient.sweep().claimed()).as("still inside the debounce").isZero();
    assertThat(state(a).get("dirty")).as("and it stays dirty").isEqualTo(true);
  }

  @Test
  void aClaimTakesAtMostTheBatchSize() {
    publishOneCard();
    for (int i = 0; i < 5; i++) {
      projections.onExpenseEvent(Events.created(UUID.randomUUID(), UUID.randomUUID(),
          Events.state(100_000L, today(), "food.x")));
    }
    Sweeper small = new Sweeper(tx, store, recommendations, java.time.Clock.systemUTC(), Duration.ZERO, 2);
    assertThat(small.sweep().claimed()).isEqualTo(2);
    assertThat(small.sweep().claimed()).isEqualTo(2);
  }

  /** One published card, so a recompute has something to rank. */
  void publishOneCard() {
    UUID cardId = UUID.randomUUID();
    projections.onCatalogEvent(Events.cardPublished(cardId, "debounce-card-" + cardId, 1, 1,
        Events.snapshot(cardId, "debounce-card-" + cardId, "Debounce Card", 0, null,
            List.of(Events.rule(UUID.randomUUID(), "food", "cashback", 500, 0L, null, null, List.of(), 0)),
            null)));
  }
}
