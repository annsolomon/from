package in.dexter.testkit.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import in.dexter.core.tenant.Tenant;
import in.dexter.events.KafkaClients;
import in.dexter.events.consumer.EventConsumer;
import in.dexter.events.relay.OutboxRelay;
import in.dexter.testkit.containers.TestKafka;
import in.dexter.testkit.demo.DemoEvents;
import in.dexter.testkit.demo.app.CreateNote;
import in.dexter.testkit.demo.config.DemoApp;
import in.dexter.testkit.demo.infra.NoteStatsProjector;
import in.dexter.testkit.support.DemoKafka;
import in.dexter.testkit.support.KafkaProbe;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** MASTER_PLAN 6.5: same-transaction outbox, relay publish + mark, kill mid-batch, leader election, partitions. */
class OutboxRelayIT {
  static final String CONSUMER = "relay-it";
  static DemoApp app;
  static Producer<String, byte[]> producer;

  @BeforeAll
  static void setUp() {
    app = DemoApp.get();
    DemoKafka.ensureTopics(CONSUMER);
    producer = KafkaClients.idempotentProducer(TestKafka.bootstrapServers(), "relay-it");
  }

  @AfterAll
  static void tearDown() {
    producer.close();
  }

  static OutboxRelay relay(OutboxRelay.Settings s, OutboxRelay.Hooks hooks) {
    return new OutboxRelay(app.db.unpooled("relay"), producer, s, hooks, List.of());
  }

  static void createNotes(UUID account, int n) {
    for (int i = 0; i < n; i++) {
      app.service.create(Tenant.user(account), UUID.randomUUID(), new CreateNote("n" + i, "note", 100));
    }
  }

  static long unpublished(String where) {
    return app.admin.sql("SELECT count(1) FROM demo.outbox WHERE published_at IS NULL AND " + where)
        .query(Long.class).single();
  }

  static long unpublishedFor(UUID account) {
    return app.admin.sql("SELECT count(1) FROM demo.outbox WHERE published_at IS NULL AND message_key = :a")
        .param("a", account.toString()).query(Long.class).single();
  }

  /** Publishes everything other tests left unpublished, so a test's first batch holds only its own rows. */
  static void drainOutbox() {
    try (OutboxRelay r = relay(OutboxRelay.Settings.defaults("demo"), OutboxRelay.Hooks.NONE)) {
      r.start();
      await().atMost(Duration.ofSeconds(60)).until(() -> unpublished("true") == 0);
    }
  }

  @Test
  void theOutboxRowCommitsAndRollsBackWithTheBusinessWrite() {
    UUID a = UUID.randomUUID();
    app.service.failNextCreate();
    assertThatThrownBy(() -> createNotes(a, 1)).hasMessageContaining("injected failure");
    assertThat(unpublishedFor(a)).isZero();
    createNotes(a, 1);
    assertThat(unpublishedFor(a)).isEqualTo(1);
  }

  @Test
  void relayPublishesInOrderAndMarksRows() {
    UUID a = UUID.randomUUID();
    createNotes(a, 5);
    try (OutboxRelay r = relay(OutboxRelay.Settings.defaults("demo"), OutboxRelay.Hooks.NONE)) {
      r.start();
      await().atMost(Duration.ofSeconds(60)).until(() -> unpublishedFor(a) == 0);
    }
    List<ConsumerRecord<String, byte[]>> got = KafkaProbe.read(DemoEvents.TOPIC, rec -> a.toString().equals(rec.key()),
        l -> l.size() >= 5, Duration.ofSeconds(30));
    assertThat(got).hasSize(5);
    List<String> titles = got.stream()
        .map(rec -> app.codec.decode(rec.value()))
        .peek(e -> assertThat(e.accountId()).isEqualTo(a))
        .map(e -> e.data().get("title").asString()).toList();
    assertThat(titles).containsExactly("n0", "n1", "n2", "n3", "n4"); // per-account order preserved
    assertThat(new String(got.getFirst().headers().lastHeader("ce_type").value(), StandardCharsets.UTF_8))
        .isEqualTo(DemoEvents.NOTE_CREATED.type());
  }

  @Test
  void killingTheRelayMidBatchLosesNothingAndConsumersDedupe() throws Exception {
    drainOutbox();
    UUID b = UUID.randomUUID();
    createNotes(b, 10);
    AtomicBoolean killed = new AtomicBoolean();
    OutboxRelay.Hooks killAfterThree = new OutboxRelay.Hooks() {
      @Override
      public void afterSend(int sentInBatch) {
        if (sentInBatch == 3 && killed.compareAndSet(false, true)) {
          throw new IllegalStateException("relay killed mid-batch");
        }
      }
    };
    try (OutboxRelay a = relay(OutboxRelay.Settings.defaults("demo").withStandbyRetry(Duration.ofSeconds(60)),
        killAfterThree)) {
      a.start();
      await().atMost(Duration.ofSeconds(30)).untilTrue(killed);
    }
    assertThat(unpublishedFor(b)).as("the killed batch rolled back").isEqualTo(10);

    try (OutboxRelay successor = relay(OutboxRelay.Settings.defaults("demo"), OutboxRelay.Hooks.NONE)) {
      successor.start();
      await().atMost(Duration.ofSeconds(60)).until(() -> unpublishedFor(b) == 0);
    }
    List<ConsumerRecord<String, byte[]>> onTopic = KafkaProbe.read(DemoEvents.TOPIC, rec -> b.toString().equals(rec.key()),
        l -> l.size() >= 13, Duration.ofSeconds(30));
    Set<UUID> distinct = new HashSet<>();
    onTopic.forEach(rec -> distinct.add(app.codec.decode(rec.value()).id()));
    assertThat(onTopic.size()).as("at-least-once: the 3 sends before the kill are on the topic twice").isGreaterThanOrEqualTo(13);
    assertThat(distinct).hasSize(10);

    NoteStatsProjector projector = new NoteStatsProjector(CONSUMER, app.tx, app.inbox, app.jdbc, app.codec);
    EventConsumer.Settings cs = new EventConsumer.Settings(TestKafka.bootstrapServers(), "demo", CONSUMER,
        List.of(DemoEvents.TOPIC), DemoKafka.FAST_STAGES, 200, Duration.ofMillis(200));
    try (EventConsumer consumer = new EventConsumer(cs, projector, app.codec, producer, Clock.systemUTC())) {
      consumer.start();
      await().atMost(Duration.ofSeconds(60)).until(() -> stats(app.admin, b) == 10);
      await().atMost(Duration.ofSeconds(30)).until(() -> projector.duplicatesSkipped.get() >= 3);
    }
    assertThat(stats(app.admin, b)).as("exactly-once effect").isEqualTo(10);
    assertThat(app.admin.sql("SELECT count(1) FROM demo.inbox WHERE consumer = :c AND event_id = ANY(:ids)")
        .param("c", CONSUMER).param("ids", in.dexter.db.sql.Sql.uuids(distinct)).query(Long.class).single())
        .isEqualTo(10);
  }

  static long stats(JdbcClient admin, UUID account) {
    return admin.sql("SELECT coalesce(max(notes), 0) FROM demo.note_stats WHERE account_id = :a").param("a", account)
        .query(Long.class).single();
  }

  @Test
  void onlyOneRelayLeadsAndTheStandbyTakesOver() {
    OutboxRelay.Settings s = OutboxRelay.Settings.defaults("demo").withStandbyRetry(Duration.ofMillis(200));
    try (OutboxRelay r1 = relay(s, OutboxRelay.Hooks.NONE); OutboxRelay r2 = relay(s, OutboxRelay.Hooks.NONE)) {
      r1.start();
      r2.start();
      await().atMost(Duration.ofSeconds(30)).until(() -> r1.isLeader() || r2.isLeader());
      for (int i = 0; i < 10; i++) {
        assertThat(r1.isLeader() && r2.isLeader()).as("never two leaders").isFalse();
      }
      OutboxRelay leader = r1.isLeader() ? r1 : r2;
      OutboxRelay standby = leader == r1 ? r2 : r1;
      leader.close();
      await().atMost(Duration.ofSeconds(30)).until(standby::isLeader);
      UUID a = UUID.randomUUID();
      createNotes(a, 2);
      await().atMost(Duration.ofSeconds(30)).until(() -> unpublishedFor(a) == 0);
    }
  }

  @Test
  void leaderKeepsPartitionsAhead() {
    try (OutboxRelay r = relay(OutboxRelay.Settings.defaults("demo"), OutboxRelay.Hooks.NONE)) {
      r.start();
      await().atMost(Duration.ofSeconds(30)).until(r::isLeader);
      await().atMost(Duration.ofSeconds(30)).until(r::partitionsHealthy);
    }
    assertThat(app.admin.sql("SELECT to_regclass('demo.outbox_p' || to_char((now() AT TIME ZONE 'UTC') + interval '3 days',"
        + " 'YYYYMMDD')) IS NOT NULL").query(Boolean.class).single()).isTrue();
  }

}
