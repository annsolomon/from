package in.dexter.catalog.config;

import in.dexter.events.InboxPurgeJob;
import in.dexter.events.KafkaClients;
import in.dexter.events.relay.OutboxRelay;
import in.dexter.events.topics.TopicRegistry;
import in.dexter.events.Topics;
import java.time.Duration;
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
 * The outbox relay of catalog (MASTER_PLAN 6.3), connected as {@code catalog_relay} on its own unpooled
 * DataSource (its session advisory lock elects one leader across instances). Maintenance tasks on the leader:
 * create catalog's topics (Kafka auto-create is off, MASTER_PLAN 6.2) and purge inbox rows (14 days); catalog has
 * no Idempotency-Key endpoints, so no key purge. Off unless {@code dexter.catalog.relay.enabled=true}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dexter.catalog.relay", name = "enabled", havingValue = "true")
public class CatalogRelayConfiguration {
  static final String SCHEMA = "catalog";

  @Bean
  SmartLifecycle catalogOutboxRelay(CatalogProperties props) {
    CatalogProperties.Relay r = props.relay();
    if (r.url() == null || r.username() == null || r.bootstrapServers() == null) {
      throw new IllegalStateException("dexter.catalog.relay.{url,username,password,bootstrap-servers} are required");
    }
    SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.postgresql.Driver(), r.url(), r.username(),
        r.password());
    JdbcClient relayJdbc = JdbcClient.create(ds);
    JdbcTransactionManager relayTx = new JdbcTransactionManager(ds);
    Producer<String, byte[]> producer = KafkaClients.idempotentProducer(r.bootstrapServers(), SCHEMA + "-relay");
    TopicRegistry topics = new TopicRegistry(r.partitions(), r.replication());
    // the card snapshot topic is compacted (latest snapshot per card = the projection); offers are a normal topic
    topics.compacted(Topics.main(SCHEMA, "card"));
    topics.main(SCHEMA, "offer");
    EnsureTopics ensureTopics = new EnsureTopics(topics, r.bootstrapServers());
    OutboxRelay.Settings d = OutboxRelay.Settings.defaults(SCHEMA);
    // Library defaults, but maintenance every 10 min (default 1 h) so a failed topic creation is retried soon.
    OutboxRelay.Settings settings = new OutboxRelay.Settings(d.schema(), d.batchSize(), d.pollInterval(),
        d.standbyRetry(), d.partitionsAhead(), d.retention(), Duration.ofMinutes(10), d.sendTimeout());
    OutboxRelay relay = new OutboxRelay(ds, producer, settings, OutboxRelay.Hooks.NONE,
        List.of(ensureTopics, new InboxPurgeJob(relayJdbc, relayTx, SCHEMA, 200)));
    return new Lifecycle(relay, producer, ensureTopics);
  }

  /** Creates catalog's topics once (idempotent; retried on the next maintenance run until it succeeds). */
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
          AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000, AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000))) {
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
}
