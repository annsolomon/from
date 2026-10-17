package in.dexter.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.catalog.support.CatalogIT;
import in.dexter.catalog.support.Cards;
import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.EventEnvelope;
import in.dexter.events.KafkaClients;
import in.dexter.events.consumer.EventConsumer;
import in.dexter.testkit.containers.TestKafka;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * MASTER_PLAN P09 DoD: the snapshot event, consumed by a test consumer, builds an identical projection. The relay
 * publishes {@code catalog.card.published} to the compacted topic {@code dexter.catalog.card.v1}; a consumer keeps the
 * latest snapshot per card (by aggregateVersion, like a real projection); after two publishes the projection equals
 * the catalog's current version byte for byte (as JSON) and the public detail.
 */
class CatalogProjectionIT extends CatalogIT {

  record Projected(long aggregateVersion, String status, int version, JsonNode snapshot) {}

  @Test
  void snapshotEventsBuildAnIdenticalProjection() throws Exception {
    String slug = "proj-" + Cards.suffix();
    String issuer = newIssuer();
    UUID id = newCard(Cards.doc(slug, "Projection Card " + slug, issuer, "visa", 10_000));
    assertThat(publish(id).status()).isEqualTo(200);
    Res draft = as(editor(), "GET", "/v1/admin/catalog/cards/" + id + "/draft", null);
    Map<String, Object> v2 = Cards.doc(slug, "Projection Card " + slug + " v2", issuer, "visa", 20_000);
    assertThat(as(editor(), "PUT", "/v1/admin/catalog/cards/" + id + "/draft", v2, "If-Match", draft.header("ETag"))
        .status()).isEqualTo(200);
    assertThat(publish(id).json().get("version").asInt()).isEqualTo(2);

    EnvelopeCodec codec = new EnvelopeCodec(JSON);
    Map<UUID, Projected> projection = new ConcurrentHashMap<>();
    EventConsumer.Handler handler = (EventEnvelope e) -> {
      if (!e.type().startsWith("catalog.card.")) {
        return;
      }
      CardPublishedV1 p = JSON.treeToValue(e.data(), CardPublishedV1.class);
      projection.merge(p.cardId(), new Projected(e.aggregateVersion(), p.status(), p.version(),
              JSON.valueToTree(p.snapshot())),
          (old, neu) -> neu.aggregateVersion() > old.aggregateVersion() ? neu : old);
    };
    EventConsumer.Settings settings = new EventConsumer.Settings(TestKafka.bootstrapServers(), "catalog",
        "projection-test-" + Cards.suffix(), List.of(CardPublishedV1.TOPIC), List.of(), 200, Duration.ofMillis(200));
    try (Producer<String, byte[]> producer = KafkaClients.idempotentProducer(TestKafka.bootstrapServers(), "test");
         EventConsumer consumer = new EventConsumer(settings, handler, codec, producer, Clock.systemUTC())) {
      consumer.start();
      long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      while ((projection.get(id) == null || projection.get(id).version() < 2) && System.nanoTime() < until) {
        Thread.sleep(200);
      }
    }
    Projected p = projection.get(id);
    assertThat(p).as("both publishes reached the consumer").isNotNull();
    assertThat(p.version()).isEqualTo(2);
    assertThat(p.status()).isEqualTo("published");

    JsonNode stored = JSON.readTree(ADMIN.sql("SELECT snapshot::text FROM catalog.card_product_versions"
        + " WHERE card_product_id = :c AND effective_to IS NULL").param("c", id).query(String.class).single());
    assertThat(p.snapshot()).as("projection = the current version").isEqualTo(stored);
    JsonNode detail = anon("GET", "/v1/catalog/cards/" + slug).json();
    ((tools.jackson.databind.node.ObjectNode) detail).remove("version");
    assertThat(p.snapshot()).as("projection = the public detail").isEqualTo(detail);
    assertThat(p.snapshot().get("annualFeePaise").asLong()).isEqualTo(20_000);
  }
}
