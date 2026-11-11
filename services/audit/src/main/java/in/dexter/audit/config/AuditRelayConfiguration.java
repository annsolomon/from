package in.dexter.audit.config;

import in.dexter.audit.app.AuditEvents;
import in.dexter.audit.app.EventIngest;
import in.dexter.audit.app.PrivacyService;
import in.dexter.contracts.events.gateway.ActivityRecordedV1;
import in.dexter.contracts.events.privacy.PrivacyCompletedV1;
import in.dexter.db.partition.PartitionManager;
import in.dexter.events.InboxPurgeJob;
import in.dexter.events.KafkaClients;
import in.dexter.events.consumer.EventConsumer;
import in.dexter.events.relay.OutboxRelay;
import in.dexter.events.topics.TopicRegistry;
import in.dexter.idempotency.IdempotencyPurgeJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * The outbox relay of audit (MASTER_PLAN 6.3) as {@code audit_relay} on its own unpooled DataSource (its session
 * advisory lock elects one leader). Maintenance on the leader: create audit's topic, its three consumers' stages and
 * every source topic it consumes (declared exactly as their producers declare them, A-67); purge inbox rows and
 * Idempotency-Key rows; keep audit_log's monthly partitions three months ahead (never dropped: the log is kept)
 * and activity_events' daily ones three days ahead and inside {@code activityRetention} (90 days).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dexter.audit.relay", name = "enabled", havingValue = "true")
public class AuditRelayConfiguration {
  static final String SCHEMA = "audit";
  private static final Logger log = LoggerFactory.getLogger(AuditRelayConfiguration.class);

  @Bean
  SmartLifecycle auditOutboxRelay(AuditProperties props) {
    AuditProperties.Relay r = props.relay();
    if (r.url() == null || r.username() == null || r.bootstrapServers() == null) {
      throw new IllegalStateException("dexter.audit.relay.{url,username,password,bootstrap-servers} are required");
    }
    SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.postgresql.Driver(), r.url(), r.username(),
        r.password());
    JdbcClient relayJdbc = JdbcClient.create(ds);
    JdbcTransactionManager relayTx = new JdbcTransactionManager(ds);
    Producer<String, byte[]> producer = KafkaClients.idempotentProducer(r.bootstrapServers(), SCHEMA + "-relay");
    TopicRegistry topics = topics(r.partitions(), r.replication(), props.privacy().services());
    Runnable ensureTopics = new EnsureTopics(topics, r.bootstrapServers());
    OutboxRelay.Settings d = OutboxRelay.Settings.defaults(SCHEMA);
    OutboxRelay.Settings settings = new OutboxRelay.Settings(d.schema(), d.batchSize(), d.pollInterval(),
        d.standbyRetry(), d.partitionsAhead(), d.retention(), Duration.ofMinutes(10), d.sendTimeout());
    PartitionManager partitions = new PartitionManager(relayJdbc, relayTx, SCHEMA);
    Runnable keepPartitions = () -> {
      try {
        partitions.createAhead("audit_log", PartitionManager.Grain.MONTH, 3);
        partitions.createAhead("activity_events", PartitionManager.Grain.DAY, 3);
        partitions.dropOlderThan("activity_events", Instant.now().minus(r.activityRetention()), null);
      } catch (RuntimeException e) {
        log.warn("AUDIT_PARTITIONS_FAILED error={}", e.toString());
      }
    };
    OutboxRelay relay = new OutboxRelay(ds, producer, settings, OutboxRelay.Hooks.NONE,
        List.of(ensureTopics, new InboxPurgeJob(relayJdbc, relayTx, SCHEMA, 200),
            new IdempotencyPurgeJob(relayJdbc, relayTx, SCHEMA, 200), keepPartitions));
    return new Lifecycle(relay, producer, ensureTopics);
  }

  /** audit's own topic, the sources as their producers declare them, and the three consumers' stages. */
  static TopicRegistry topics(int partitions, short replication, List<String> participants) {
    TopicRegistry topics = new TopicRegistry(partitions, replication);
    AuditEvents.AGGREGATES.forEach(a -> topics.main(SCHEMA, a));
    List.of("account", "session", "security", "admin").forEach(a -> topics.main("identity", a));
    topics.main("billing", "subscription");
    topics.main("commerce", "application");
    topics.main("planning", "budget");
    topics.main("gateway", "activity");
    participants.forEach(s -> topics.main(s, PrivacyCompletedV1.AGGREGATE));
    List<String> stages = EventConsumer.Settings.defaults("x", SCHEMA, "x", List.of("x")).stageNames();
    EventIngest.EVENT_TOPICS.forEach(t -> topics.consumer(t, EventIngest.EVENTS_CONSUMER, stages));
    topics.consumer(ActivityRecordedV1.TOPIC, EventIngest.ACTIVITY_CONSUMER, stages);
    participants.forEach(s -> topics.consumer(PrivacyCompletedV1.topic(s), PrivacyService.ACK_CONSUMER, stages));
    return topics;
  }

  /** Creates the topics once (idempotent; retried on the next maintenance run until it succeeds). */
  static final class EnsureTopics implements Runnable {
    private final TopicRegistry topics;
    private final String bootstrap;
    private final AtomicBoolean done = new AtomicBoolean();

    EnsureTopics(TopicRegistry topics, String bootstrap) {
      this.topics = topics;
      this.bootstrap = bootstrap;
    }

    @Override
    public void run() {
      if (done.get()) {
        return;
      }
      try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
          AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
          AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000))) {
        List<String> created = topics.ensure(admin, Duration.ofSeconds(10));
        done.set(true);
        log.info("TOPICS_READY created={}", created);
      } catch (RuntimeException e) {
        log.warn("TOPICS_NOT_READY error={}", e.toString());
      }
    }
  }

  /** Starts the relay after the context is up and stops it (releasing leadership) first on shutdown. */
  static final class Lifecycle implements SmartLifecycle {
    private final OutboxRelay relay;
    private final Producer<String, byte[]> producer;
    private final Runnable ensureTopics;

    Lifecycle(OutboxRelay relay, Producer<String, byte[]> producer, Runnable ensureTopics) {
      this.relay = relay;
      this.producer = producer;
      this.ensureTopics = ensureTopics;
    }

    @Override
    public void start() {
      ensureTopics.run();
      relay.start();
    }

    @Override
    public void stop() {
      relay.close();
      producer.close();
    }

    @Override
    public boolean isRunning() {
      return relay.isRunning();
    }

    @Override
    public int getPhase() {
      return Integer.MAX_VALUE - 100;
    }
  }
}
