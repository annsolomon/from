package in.dexter.testkit.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import in.dexter.contracts.events.demo.NoteCreatedV1;
import in.dexter.contracts.events.demo.NoteRenamedV1;
import in.dexter.core.id.Ids;
import in.dexter.events.EventEnvelope;
import in.dexter.events.EventType;
import in.dexter.events.KafkaClients;
import in.dexter.events.consumer.DlqReplay;
import in.dexter.events.consumer.EventConsumer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
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
    // Spring Boot adds its registries to the global one; a plain IT gives it one so the event meters hold values.
    Metrics.addRegistry(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
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

  /**
   * P19 (docs/runbooks/dlq-replay.md): a dead letter is visible on the dexter_dlq_messages gauge; once the cause is
   * fixed, replay puts it back on the consumer's retry topic, it is applied once, and it is gone from the DLQ.
   */
  @Test
  void aDeadLetterIsCountedThenReplayedOnceTheCauseIsFixed() throws Exception {
    UUID a = UUID.randomUUID();
    String title = "fixed-later-" + a;
    projector.failTitle(title, 3); // fails the first delivery and both retries → DLQ
    publish(created(a, UUID.randomUUID(), title, 77));
    List<ConsumerRecord<String, byte[]>> dead = KafkaProbe.read(DemoKafka.dlq(CONSUMER),
        r -> a.toString().equals(r.key()), l -> !l.isEmpty(), Duration.ofSeconds(60));
    assertThat(dead).hasSize(1);
    assertThat(stats(a)[0]).isZero();
    // the main stage measures its DLQ depth every 30 s
    await().atMost(Duration.ofSeconds(75)).until(() -> dlqGauge() > 0);

    DlqReplay.Result dry = DlqReplay.replay(TestKafka.bootstrapServers(), producer, DemoEvents.TOPIC, CONSUMER,
        "retry-1m", r -> a.toString().equals(r.key()), true);
    assertThat(dry.replayed()).isEqualTo(1);
    assertThat(stats(a)[0]).isZero();

    // the cause is fixed (the injected failures are used up): replay only this account's message
    DlqReplay.Result r = DlqReplay.replay(TestKafka.bootstrapServers(), producer, DemoEvents.TOPIC, CONSUMER,
        "retry-1m", rec -> a.toString().equals(rec.key()), false);
    assertThat(r.replayed()).isEqualTo(1);
    assertThat(r.target()).isEqualTo(DemoKafka.retryTopic(CONSUMER, "retry-1m"));
    await().atMost(Duration.ofSeconds(60)).until(() -> stats(a)[0] == 1);
    assertThat(stats(a)).containsExactly(1, 77);
    List<ConsumerRecord<String, byte[]>> replayed = KafkaProbe.read(DemoKafka.retryTopic(CONSUMER, "retry-1m"),
        rec -> a.toString().equals(rec.key()) && rec.headers().lastHeader(DlqReplay.H_REPLAYED) != null,
        l -> !l.isEmpty(), Duration.ofSeconds(20));
    assertThat(header(replayed.getFirst(), DlqReplay.H_REPLAYED)).isEqualTo("1");
    assertThat(header(replayed.getFirst(), EventConsumer.H_ATTEMPT)).isEqualTo("1");
    // Replay is safe to repeat: a filtered replay leaves the DLQ in place where other messages sit before this one
    // on its partition, so the same dead letter can be replayed twice; the inbox makes the second a no-op.
    int skippedBefore = projector.duplicatesSkipped.get();
    DlqReplay.Result again = DlqReplay.replay(TestKafka.bootstrapServers(), producer, DemoEvents.TOPIC, CONSUMER,
        "retry-1m", rec -> a.toString().equals(rec.key()), false);
    if (again.replayed() > 0) {
      await().atMost(Duration.ofSeconds(60)).until(() -> projector.duplicatesSkipped.get() > skippedBefore);
    }
    assertThat(stats(a)).containsExactly(1, 77);
  }

  @Test
  void anUnfilteredReplayEmptiesTheDlq() throws Exception {
    UUID a = UUID.randomUUID();
    String title = "fixed-later-" + a;
    projector.failTitle(title, 3);
    publish(created(a, UUID.randomUUID(), title, 5));
    KafkaProbe.read(DemoKafka.dlq(CONSUMER), r -> a.toString().equals(r.key()), l -> !l.isEmpty(),
        Duration.ofSeconds(60));
    DlqReplay.Result r = DlqReplay.replay(TestKafka.bootstrapServers(), producer, DemoEvents.TOPIC, CONSUMER,
        "retry-1m", rec -> true, false);
    assertThat(r.replayed()).isGreaterThanOrEqualTo(1);
    assertThat(r.kept()).isZero();
    assertThat(r.truncatedTo()).isNotEmpty();
    await().atMost(Duration.ofSeconds(60)).until(() -> stats(a)[0] == 1);
    // what was on the DLQ when the replay started is gone (messages that fail again come back after it)
    try (var kc = new org.apache.kafka.clients.consumer.KafkaConsumer<String, byte[]>(java.util.Map.of(
        "bootstrap.servers", TestKafka.bootstrapServers()), new org.apache.kafka.common.serialization.StringDeserializer(),
        new org.apache.kafka.common.serialization.ByteArrayDeserializer())) {
      r.truncatedTo().forEach((partition, offset) -> {
        var tp = new org.apache.kafka.common.TopicPartition(DemoKafka.dlq(CONSUMER), partition);
        assertThat(kc.beginningOffsets(List.of(tp)).get(tp)).isGreaterThanOrEqualTo(offset);
      });
    }
  }

  static double dlqGauge() {
    Gauge g = Metrics.globalRegistry.find("dexter.dlq.messages").tag("consumer", CONSUMER).gauge();
    return g == null ? 0 : g.value();
  }

  static String header(ConsumerRecord<String, byte[]> r, String k) {
    return new String(r.headers().lastHeader(k).value(), StandardCharsets.UTF_8);
  }
}
