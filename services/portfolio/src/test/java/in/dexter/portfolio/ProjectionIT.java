package in.dexter.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.EventEnvelope;
import in.dexter.events.KafkaClients;
import in.dexter.events.consumer.EventConsumer;
import in.dexter.events.topics.TopicRegistry;
import in.dexter.portfolio.app.ProjectionService;
import in.dexter.portfolio.app.ProjectionService.Outcome;
import in.dexter.portfolio.support.Events;
import in.dexter.portfolio.support.PortfolioIT;
import in.dexter.testkit.containers.TestKafka;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The catalog projection (MASTER_PLAN P11 "projection version guard"): a newer aggregate version replaces the row,
 * an older or equal one is ignored, a redelivered event id is skipped (inbox); retired cards stay readable but cannot
 * be added. End to end through Kafka: envelopes on the real topics reach the handlers the service wires.
 */
class ProjectionIT extends PortfolioIT {
  @Autowired
  ProjectionService projections;
  @Autowired
  EnvelopeCodec codec;

  static Map<String, Object> snapshot(UUID id, String name, long fee) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("id", id.toString());
    s.put("slug", "proj-" + id.toString().substring(0, 8));
    s.put("name", name);
    s.put("issuerCode", "MOCK_PROJ");
    s.put("network", "mastercard");
    s.put("tier", "mid");
    s.put("annualFeePaise", fee);
    s.put("rules", List.of());
    return s;
  }

  long fee(UUID id) {
    return ADMIN.sql("SELECT annual_fee_paise FROM portfolio.catalog_products_proj WHERE card_product_id = :c")
        .param("c", id).query(Long.class).single();
  }

  @Test
  void theVersionGuardKeepsTheNewestSnapshot() {
    UUID id = UUID.randomUUID();
    EventEnvelope v3 = Events.card(id, "proj-x", "published", 2, 3, snapshot(id, "MOCK_Proj Card v2", 99_900));
    EventEnvelope v1 = Events.card(id, "proj-x", "published", 1, 1, snapshot(id, "MOCK_Proj Card", 49_900));
    assertThat(projections.onCatalogEvent(v3)).isEqualTo(Outcome.APPLIED);
    assertThat(projections.onCatalogEvent(v1)).as("older, arriving late (retry stage)").isEqualTo(Outcome.STALE);
    assertThat(projections.onCatalogEvent(v3)).as("redelivered").isEqualTo(Outcome.DUPLICATE);
    EventEnvelope sameVersion = Events.card(id, "proj-x", "published", 2, 3, snapshot(id, "Other", 1));
    assertThat(projections.onCatalogEvent(sameVersion)).as("same aggregate version, new event id").isEqualTo(Outcome.STALE);
    assertThat(fee(id)).isEqualTo(99_900);

    UUID a = UUID.randomUUID();
    Res added = post("/v1/cards", a, card(id, "7777", 10));
    assertThat(added.status()).as(added.toString()).isEqualTo(201);
    assertThat(added.json().get("network").asString()).isEqualTo("mastercard");
    EventEnvelope retired = Events.card(id, "proj-x", "retired", 2, 4, snapshot(id, "MOCK_Proj Card v2", 99_900));
    assertThat(projections.onCatalogEvent(retired)).isEqualTo(Outcome.APPLIED);
    assertThat(post("/v1/cards", a, card(id, "8888", 10)).json().get("errors").get(0).get("code").asString())
        .isEqualTo("PRODUCT_RETIRED");
    assertThat(get("/v1/cards/" + added.json().get("id").asString(), a).json().get("productName").asString())
        .as("existing cards keep their product").isEqualTo("MOCK_Proj Card v2");
    assertThat(projections.onCatalogEvent(new EventEnvelope(UUID.randomUUID(), "catalog.offer.changed", 1, "catalog",
        java.time.Instant.now(), null, id, 9, null, JSON.createObjectNode()))).isEqualTo(Outcome.IGNORED);
  }

  @Test
  void eventsOnTheRealTopicsReachTheHandlers() throws Exception {
    String bootstrap = TestKafka.bootstrapServers();
    try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
      new TopicRegistry(1, (short) 1).compacted(CardPublishedV1.TOPIC).main("ledger", "expense")
          .ensure(admin, Duration.ofSeconds(20));
    }
    UUID product = UUID.randomUUID();
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, POINTS_CARD);
    record(a, card, txn("2026-09-02T05:00:00Z", 33_300, "purchase", "DECATHLON PUNE", "shopping"));
    UUID expense = UUID.randomUUID();
    String group = "it-" + UUID.randomUUID().toString().substring(0, 8);
    try (Producer<String, byte[]> producer = KafkaClients.idempotentProducer(bootstrap, "it");
         EventConsumer c1 = new EventConsumer(new EventConsumer.Settings(bootstrap, "portfolio", group + "-c",
             List.of(CardPublishedV1.TOPIC), List.of(), 200, Duration.ofMillis(200)), projections::onCatalogEvent,
             codec, producer, Clock.systemUTC());
         EventConsumer c2 = new EventConsumer(new EventConsumer.Settings(bootstrap, "portfolio", group + "-e",
             List.of(ExpenseChangedV1.TOPIC), List.of(), 200, Duration.ofMillis(200)), projections::onExpenseEvent,
             codec, producer, Clock.systemUTC())) {
      EventEnvelope cardEvent = Events.card(product, "proj-kafka", "published", 1, 1, snapshot(product, "MOCK_Kafka",
          12_300));
      EventEnvelope expenseEvent = Events.created(a, expense, Events.state(33_300, "2026-09-02", card, "Decathlon"));
      producer.send(new ProducerRecord<>(CardPublishedV1.TOPIC, cardEvent.messageKey(), codec.encode(cardEvent))).get();
      producer.send(new ProducerRecord<>(ExpenseChangedV1.TOPIC, expenseEvent.messageKey(),
          codec.encode(expenseEvent))).get();
      c1.start();
      c2.start();
      long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      while (System.nanoTime() < until && (count("catalog_products_proj WHERE card_product_id = '" + product + "'") == 0
          || count("expense_links WHERE expense_id = '" + expense + "'") == 0)) {
        Thread.sleep(200);
      }
    }
    assertThat(fee(product)).isEqualTo(12_300);
    assertThat(count("expense_links WHERE expense_id = '" + expense + "' AND status = 'suggested'")).isEqualTo(1);
  }

  static long count(String fromWhere) {
    return ADMIN.sql("SELECT count(1) FROM portfolio." + fromWhere).query(Long.class).single();
  }
}
