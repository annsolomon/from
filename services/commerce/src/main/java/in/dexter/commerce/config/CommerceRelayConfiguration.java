package in.dexter.commerce.config;

import in.dexter.commerce.app.CommerceEvents;
import in.dexter.commerce.app.ProjectionService;
import in.dexter.contracts.events.billing.EntitlementsChangedV1;
import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.portfolio.CardChangedV1;
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
 * The outbox relay of commerce (MASTER_PLAN 6.3), connected as {@code commerce_relay} on its own unpooled DataSource
 * (its session advisory lock elects one leader across instances). Maintenance tasks on the leader: create commerce's
 * topic, its three consumers' retry/DLQ topics and the catalog, portfolio and billing topics it consumes (declared
 * exactly as their producers declare them, A-67); purge inbox rows (14 days) and Idempotency-Key rows (24 h); and
 * keep the daily partitions of {@code deal_clicks} and {@code ad_impressions_raw} three days ahead and inside their
 * retention ({@code create_partitions_ahead} is granted to {@code commerce_relay} alone). Off unless
 * {@code dexter.commerce.relay.enabled=true}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dexter.commerce.relay", name = "enabled", havingValue = "true")
public class CommerceRelayConfiguration {
  static final String SCHEMA = "commerce";

  @Bean
  SmartLifecycle commerceOutboxRelay(CommerceProperties props) {
    CommerceProperties.Relay r = props.relay();
    if (r.url() == null || r.username() == null || r.bootstrapServers() == null) {
      throw new IllegalStateException("dexter.commerce.relay.{url,username,password,bootstrap-servers} are required");
    }
    SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.postgresql.Driver(), r.url(), r.username(),
        r.password());
    JdbcClient relayJdbc = JdbcClient.create(ds);
    JdbcTransactionManager relayTx = new JdbcTransactionManager(ds);
    Producer<String, byte[]> producer = KafkaClients.idempotentProducer(r.bootstrapServers(), SCHEMA + "-relay");
    TopicRegistry topics = new TopicRegistry(r.partitions(), r.replication());
    CommerceEvents.AGGREGATES.forEach(a -> topics.main(SCHEMA, a));
    // the three sources commerce consumes, declared as their producers declare them, plus each consumer's stages
    topics.main("catalog", "card");
    topics.main("portfolio", "card");
    topics.main("billing", "entitlements");
    List<String> stages = EventConsumer.Settings.defaults("x", SCHEMA, "x", List.of("x")).stageNames();
    topics.consumer(CardPublishedV1.TOPIC, ProjectionService.CATALOG_CONSUMER, stages);
    topics.consumer(CardChangedV1.TOPIC, ProjectionService.CARD_CONSUMER, stages);
    topics.consumer(EntitlementsChangedV1.TOPIC, ProjectionService.ENTITLEMENTS_CONSUMER, stages);
    EnsureTopics ensureTopics = new EnsureTopics(topics, r.bootstrapServers());
    OutboxRelay.Settings d = OutboxRelay.Settings.defaults(SCHEMA);
    // Library defaults, but maintenance every 10 min (default 1 h) so a failed topic creation is retried soon.
    OutboxRelay.Settings settings = new OutboxRelay.Settings(d.schema(), d.batchSize(), d.pollInterval(),
        d.standbyRetry(), d.partitionsAhead(), d.retention(), Duration.ofMinutes(10), d.sendTimeout());
    OutboxRelay relay = new OutboxRelay(ds, producer, settings, OutboxRelay.Hooks.NONE,
        List.of(ensureTopics, new InboxPurgeJob(relayJdbc, relayTx, SCHEMA, 200),
            new IdempotencyPurgeJob(relayJdbc, relayTx, SCHEMA, 200),
            new RawPartitions(new PartitionManager(relayJdbc, relayTx, SCHEMA), r.rawRetention())));
    return new Lifecycle(relay, producer, ensureTopics);
  }

  /** Creates commerce's topics once (idempotent; retried on the next maintenance run until it succeeds). */
  static final class EnsureTopics implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(EnsureTopics.class);
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

    /** Topics first (best effort, ~10 s max), so the first publish never waits on a missing topic. */
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

  /**
   * Keeps the raw click / impression tables' daily partitions three days ahead and drops those entirely past
   * the retention. Runs on the relay leader as {@code commerce_relay}: the SECURITY DEFINER partition functions are
   * granted to that role only.
   */
  static final class RawPartitions implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(RawPartitions.class);
    static final List<String> TABLES = List.of("deal_clicks", "ad_impressions_raw");
    private final PartitionManager partitions;
    private final Duration retention;

    RawPartitions(PartitionManager partitions, Duration retention) {
      this.partitions = partitions;
      this.retention = retention;
    }

    @Override
    public void run() {
      for (String table : TABLES) {
        try {
          partitions.createAhead(table, PartitionManager.Grain.DAY, 3);
          partitions.dropOlderThan(table, Instant.now().minus(retention), null);
        } catch (RuntimeException e) {
          // the relay's own maintenance logs and carries on; a missed pass is retried in ten minutes
          log.warn("COMMERCE_PARTITIONS_FAILED table={} error={}", table, e.toString());
        }
      }
    }
  }
}
