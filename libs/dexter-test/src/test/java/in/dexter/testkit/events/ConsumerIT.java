package in.dexter.testkit.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import in.dexter.contracts.events.demo.NoteCreatedV1;
import in.dexter.contracts.events.demo.NoteRenamedV1;
import in.dexter.core.id.Ids;
import in.dexter.events.EventEnvelope;
import in.dexter.events.EventType;
import in.dexter.events.KafkaClients;
import in.dexter.events.consumer.EventConsumer;
import in.dexter.testkit.containers.TestKafka;
import in.dexter.testkit.demo.DemoEvents;
import in.dexter.testkit.demo.config.DemoApp;
import in.dexter.testkit.demo.infra.NoteStatsProjector;
import in.dexter.testkit.support.DemoKafka;
import in.dexter.testkit.support.KafkaProbe;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** MASTER_PLAN 6.5, consumer side: dedupe, stale versions, retries, poison → DLQ. */
class ConsumerIT {
  static final String CONSUMER = "stats";
  static DemoApp app;
  static Producer<String, byte[]> producer;
  static NoteStatsProjector projector;
  static EventConsumer consumer;

  @BeforeAll
  static void setUp() {
    app = DemoApp.get();
    DemoKafka.ensureTopics(CONSUMER);
    producer = KafkaClients.idempotentProducer(TestKafka.bootstrapServers(), "consumer-it");
    projector = new NoteStatsProjector(CONSUMER, app.tx, app.inbox, app.jdbc, app.codec);
    consumer = new EventConsumer(new EventConsumer.Settings(TestKafka.bootstrapServers(), "demo", CONSUMER,
        List.of(DemoEvents.TOPIC), DemoKafka.FAST_STAGES, 200, Duration.ofMillis(200)),
        projector, app.codec, producer, Clock.systemUTC());
    consumer.start();
  }

  @AfterAll
  static void tearDown() {
    consumer.close();
    producer.close();
  }

  static <T> EventEnvelope envelope(EventType<T> type, UUID account, UUID aggregate, long version, T data) {
    return new EventEnvelope(Ids.newId(), type.type(), type.version(), "demo", Instant.now(), account, aggregate,
        version, null, app.codec.mapper().valueToTree(data));
  }

  static EventEnvelope created(UUID account, UUID note, String title, long paise) {
    return envelope(DemoEvents.NOTE_CREATED, account, note, 1,
        new NoteCreatedV1(note, title, paise, "2026-09-27T10:00:00Z"));
  }

  static EventEnvelope renamed(UUID account, UUID note, long version, String title) {
    return envelope(DemoEvents.NOTE_RENAMED, account, note, version, new NoteRenamedV1(note, title));
  }

  static void publish(EventEnvelope e) throws Exception {
    producer.send(new ProducerRecord<>(DemoEvents.TOPIC, e.messageKey(),
        app.codec.encode(e).getBytes(StandardCharsets.UTF_8))).get();
  }

  static long[] stats(UUID account) {
    return app.admin.sql("SELECT coalesce(max(notes), 0), coalesce(max(amount_paise), 0) FROM demo.note_stats"
            + " WHERE account_id = :a").param("a", account)
        .query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
  }

  static String title(UUID account, UUID note) {
    return app.admin.sql("SELECT coalesce(max(title), '') FROM demo.note_titles WHERE account_id = :a AND note_id = :n")
        .param("a", account).param("n", note).query(String.class).single();
  }

  @Test
  void theSameEventDeliveredTwiceIsAppliedOnce() throws Exception {
    UUID a = UUID.randomUUID();
    EventEnvelope e = created(a, UUID.randomUUID(), "lunch", 500);
    publish(e);
    publish(e);
    // a later event on the same key (same partition) proves both copies were consumed first
    publish(created(a, UUID.randomUUID(), "marker", 7));
    await().atMost(Duration.ofSeconds(60)).until(() -> stats(a)[0] == 2);
    assertThat(stats(a)).containsExactly(2, 507);
  }

  @Test
  void anOlderVersionArrivingLateIsIgnored() throws Exception {
    UUID a = UUID.randomUUID();
    UUID n = UUID.randomUUID();
    publish(created(a, n, "t1", 1));
    publish(renamed(a, n, 3, "t3"));
    publish(renamed(a, n, 2, "t2")); // stale: arrives after v3
    publish(created(a, UUID.randomUUID(), "marker", 1));
    await().atMost(Duration.ofSeconds(60)).until(() -> stats(a)[0] == 2);
    assertThat(title(a, n)).isEqualTo("t3");
  }

  @Test
  void aTransientFailureIsRetriedAndAppliedOnce() throws Exception {
    UUID a = UUID.randomUUID();
    String title = "flaky-" + a;
    projector.failTitle(title, 1);
    publish(created(a, UUID.randomUUID(), title, 42));
    await().atMost(Duration.ofSeconds(60)).until(() -> stats(a)[0] == 1);
    List<ConsumerRecord<String, byte[]>> retried = KafkaProbe.read(DemoKafka.retryTopic(CONSUMER, "retry-1m"),
        r -> a.toString().equals(r.key()), l -> !l.isEmpty(), Duration.ofSeconds(20));
    assertThat(retried).hasSize(1);
    assertThat(stats(a)).containsExactly(1, 42);
  }

  @Test
  void poisonGoesStraightToTheDlq() throws Exception {
    String key = UUID.randomUUID().toString();
    producer.send(new ProducerRecord<>(DemoEvents.TOPIC, key, "definitely not json".getBytes(StandardCharsets.UTF_8)))
        .get();
    List<ConsumerRecord<String, byte[]>> dlq = KafkaProbe.read(DemoKafka.dlq(CONSUMER), r -> key.equals(r.key()),
        l -> !l.isEmpty(), Duration.ofSeconds(60));
    assertThat(dlq).hasSize(1);
    assertThat(header(dlq.getFirst(), EventConsumer.H_ATTEMPT)).isEqualTo("1");
    assertThat(header(dlq.getFirst(), EventConsumer.H_ORIGINAL_TOPIC)).isEqualTo(DemoEvents.TOPIC);
  }

  @Test
  void aHandlerThatAlwaysFailsEndsInTheDlqAfterBothRetries() throws Exception {
    UUID a = UUID.randomUUID();
    publish(created(a, UUID.randomUUID(), NoteStatsProjector.ALWAYS_FAILS, 1));
    List<ConsumerRecord<String, byte[]>> dlq = KafkaProbe.read(DemoKafka.dlq(CONSUMER), r -> a.toString().equals(r.key()),
        l -> !l.isEmpty(), Duration.ofSeconds(60));
    assertThat(dlq).hasSize(1);
    assertThat(header(dlq.getFirst(), EventConsumer.H_ATTEMPT)).isEqualTo("3");
    assertThat(header(dlq.getFirst(), EventConsumer.H_ERROR)).contains("never be projected");
    assertThat(stats(a)[0]).isZero();
  }

  static String header(ConsumerRecord<String, byte[]> r, String k) {
    return new String(r.headers().lastHeader(k).value(), StandardCharsets.UTF_8);
  }
}
