package in.dexter.identity.config;

import in.dexter.events.InboxPurgeJob;
import in.dexter.events.KafkaClients;
import in.dexter.events.relay.OutboxRelay;
import in.dexter.events.topics.TopicRegistry;
import in.dexter.identity.app.IdentityEvents;
import in.dexter.idempotency.IdempotencyPurgeJob;
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
 * The outbox relay of identity (MASTER_PLAN 6.3), connected as {@code identity_relay} on its own unpooled
 * DataSource (its session advisory lock elects one leader across instances). Maintenance tasks on the leader:
 * create identity's topics (Kafka auto-create is off, MASTER_PLAN 6.2), purge inbox rows (14 days) and
 * Idempotency-Key rows (24 h, MASTER_PLAN 5.2). Off unless {@code dexter.identity.relay.enabled=true}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dexter.identity.relay", name = "enabled", havingValue = "true")
public class IdentityRelayConfiguration {
  static final String SCHEMA = "identity";

  @Bean
  SmartLifecycle identityOutboxRelay(IdentityProperties props) {
    IdentityProperties.Relay r = props.relay();
    if (r.url() == null || r.username() == null || r.bootstrapServers() == null) {
      throw new IllegalStateException("dexter.identity.relay.{url,username,password,bootstrap-servers} are required");
    }
    SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.postgresql.Driver(), r.url(), r.username(),
        r.password());
    JdbcClient relayJdbc = JdbcClient.create(ds);
    JdbcTransactionManager relayTx = new JdbcTransactionManager(ds);
    Producer<String, byte[]> producer = KafkaClients.idempotentProducer(r.bootstrapServers(), SCHEMA + "-relay");
    TopicRegistry topics = new TopicRegistry(r.partitions(), r.replication());
    IdentityEvents.AGGREGATES.forEach(a -> topics.main(SCHEMA, a));
    OutboxRelay relay = new OutboxRelay(ds, producer, OutboxRelay.Settings.defaults(SCHEMA), OutboxRelay.Hooks.NONE,
        List.of(new EnsureTopics(topics, r.bootstrapServers()), new InboxPurgeJob(relayJdbc, relayTx, SCHEMA, 200),
            new IdempotencyPurgeJob(relayJdbc, relayTx, SCHEMA, 200)));
    return new Lifecycle(relay, producer);
  }

  /** Creates identity's topics once (idempotent; retried on the next maintenance run until it succeeds). */
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

    Lifecycle(OutboxRelay relay, Producer<String, byte[]> producer) {
      this.relay = relay;
      this.producer = producer;
    }

    @Override
    public void start() {
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
