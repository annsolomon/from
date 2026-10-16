package in.dexter.testkit.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import in.dexter.core.tenant.Tenant;
import in.dexter.events.KafkaClients;
import in.dexter.events.consumer.EventConsumer;
import in.dexter.events.relay.OutboxRelay;
import in.dexter.testkit.containers.TestKafka;
import in.dexter.testkit.demo.app.CreateNote;
import in.dexter.testkit.demo.config.DemoApp;
import in.dexter.testkit.support.DemoKafka;
import in.dexter.testkit.support.Mvc;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Path B end to end (ADR-0004 §4): use-case → outbox → relay → Kafka → {@code NoteCacheInvalidation} →
 * generation bump. Path A is made to fail by cutting Redis during the commit, so only the event can fix the
 * cache; a Redis outage while the event is handled sends it through the retry topic instead of losing it.
 */
class DemoCacheInvalidationIT {
  static final String CONSUMER = "cache-inv";
  static DemoApp app;
  static Producer<String, byte[]> producer;
  static OutboxRelay relay;
  static EventConsumer consumer;

  @BeforeAll
  static void setUp() {
    app = DemoApp.get();
    DemoKafka.ensureTopics(CONSUMER);
    producer = KafkaClients.idempotentProducer(TestKafka.bootstrapServers(), "cache-inv-it");
    relay = new OutboxRelay(app.db.unpooled("relay"), producer, OutboxRelay.Settings.defaults("demo"),
        OutboxRelay.Hooks.NONE, List.of());
    relay.start();
    consumer = new EventConsumer(new EventConsumer.Settings(TestKafka.bootstrapServers(), "demo", CONSUMER,
        List.of(DemoEvents.TOPIC), DemoKafka.FAST_STAGES, 200, Duration.ofMillis(200)),
        app.cacheInvalidation, app.codec, producer, Clock.systemUTC());
    consumer.start();
  }

  @AfterAll
  static void tearDown() {
    consumer.close();
    relay.close();
    producer.close();
  }

  static String title(UUID account, UUID id) throws Exception {
    String body = Mvc.call(app.mvc, get("/demo/notes/" + id).header("Authorization", app.bearer(account))).body();
    int i = body.indexOf("\"title\":\"");
    return body.substring(i + 9, body.indexOf('"', i + 9));
  }

  static UUID createNote(UUID account, String title) {
    app.service.create(Tenant.user(account), UUID.randomUUID(), new CreateNote(title, "note", 100));
    return app.admin.sql("SELECT id FROM demo.notes WHERE account_id = :a").param("a", account)
        .query(UUID.class).single();
  }

  static long version(UUID account, UUID id) {
    return app.admin.sql("SELECT version FROM demo.notes WHERE account_id = :a AND id = :id").param("a", account)
        .param("id", id).query(Long.class).single();
  }

  @Test
  void theOutboxEventRepairsALostAfterCommitBump() throws Exception {
    UUID a = UUID.randomUUID();
    UUID id = createNote(a, "v1");
    assertThat(title(a, id)).isEqualTo("v1");
    app.redisProxy.cut();
    try {
      app.service.rename(Tenant.user(a), id, version(a, id), "v2"); // Path A lost
    } finally {
      DemoCacheContractIT.restore(app);
    }
    await().atMost(Duration.ofSeconds(60)).until(() -> title(a, id).equals("v2"));
  }

  @Test
  void aRedisOutageDuringPathBIsRetriedNotLost() throws Exception {
    UUID a = UUID.randomUUID();
    UUID id = createNote(a, "v1");
    assertThat(title(a, id)).isEqualTo("v1");
    long failures = app.cache.metrics().snapshot().invalidationFailures();
    app.redisProxy.cut();
    try {
      app.service.rename(Tenant.user(a), id, version(a, id), "v2"); // Path A lost
      // the consumer handles demo.note.renamed while Redis is still down → retry-1m (300 ms in tests)
      await().atMost(Duration.ofSeconds(30))
          .until(() -> app.cache.metrics().snapshot().invalidationFailures() >= failures + 2);
    } finally {
      DemoCacheContractIT.restore(app);
    }
    await().atMost(Duration.ofSeconds(60)).until(() -> title(a, id).equals("v2"));
  }
}
