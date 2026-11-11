package in.dexter.privacy;

import in.dexter.contracts.events.audit.PrivacyRequestedV1;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.InboxGuard;
import in.dexter.events.KafkaClients;
import in.dexter.events.OutboxWriter;
import in.dexter.events.consumer.EventConsumer;
import in.dexter.events.topics.TopicRegistry;
import in.dexter.objectstore.S3Objects;
import in.dexter.objectstore.SigV4;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires {@link PrivacyParticipant} into a service (ADR-0024) when {@code dexter.privacy.enabled=true}: its consumer
 * {@code privacy-<svc>} on {@code dexter.audit.privacy.v1} (retry 1 m / 10 m / DLQ of its own) and the topics it needs —
 * its own {@code dexter.<svc>.privacy.v1}, the saga's topic as the audit service declares it, and its consumer's
 * stages — so no service's relay configuration has to know about privacy. The acknowledgement goes through the
 * service's outbox, which its relay publishes. Off by default: tests and services that hold no account data never
 * start it.
 */
@AutoConfiguration(afterName = "in.dexter.events.autoconfigure.DexterEventsAutoConfiguration")
@ConditionalOnProperty(prefix = "dexter.privacy", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PrivacyProperties.class)
public class PrivacyAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  PrivacyParts privacyParts(PrivacyProperties props, ObjectProvider<Clock> clock) {
    PrivacyProperties.Store s = Objects.requireNonNull(props.store(), "dexter.privacy.store is required");
    S3Objects store = new S3Objects(new SigV4(nz(s.accessKey()), nz(s.secretKey()),
        s.region() == null || s.region().isBlank() ? "us-east-1" : s.region()),
        Objects.requireNonNull(s.endpoint(), "dexter.privacy.store.endpoint"), null,
        Objects.requireNonNull(s.bucket(), "dexter.privacy.store.bucket"), clock.getIfAvailable(Clock::systemUTC));
    return (key, json) -> store.put(key, json, "application/json");
  }

  @Bean
  PrivacyParticipant privacyParticipant(PrivacyProperties props, Environment env, JdbcClient jdbc,
      PlatformTransactionManager txManager, OutboxWriter outbox, InboxGuard inbox, PrivacyParts parts,
      EnvelopeCodec codec, ObjectProvider<Clock> clock) {
    String schema = env.getRequiredProperty("dexter.db.schema");
    return new PrivacyParticipant(schema, new HashSet<>(props.phases()), jdbc, new TransactionTemplate(txManager),
        outbox, inbox, parts, codec, clock.getIfAvailable(Clock::systemUTC), props.statementTimeout());
  }

  @Bean
  SmartLifecycle privacyConsumer(PrivacyProperties props, PrivacyParticipant participant, EnvelopeCodec codec,
      ObjectProvider<Clock> clock) {
    String bootstrap = props.bootstrapServers();
    if (bootstrap == null || bootstrap.isBlank()) {
      throw new IllegalStateException("dexter.privacy.bootstrap-servers is required");
    }
    String service = participant.service();
    Producer<String, byte[]> producer = KafkaClients.idempotentProducer(bootstrap, service + "-privacy");
    EventConsumer.Settings settings = EventConsumer.Settings.defaults(bootstrap, service,
        PrivacyRequests.consumer(service), List.of(PrivacyRequestedV1.TOPIC));
    EventConsumer consumer = new EventConsumer(settings, participant::handle, codec, producer,
        clock.getIfAvailable(Clock::systemUTC));
    TopicRegistry topics = topics(service, settings.stageNames(), props.partitions(), props.replication());
    return new Lifecycle(consumer, producer, topics, bootstrap);
  }

  /** The participant's topics: {@code dexter.<svc>.privacy.v1}, {@code dexter.audit.privacy.v1} and the stages. */
  public static TopicRegistry topics(String service, List<String> stages, int partitions, short replication) {
    return new TopicRegistry(partitions, replication)
        .main(service, "privacy")
        .main("audit", "privacy")
        .consumer(PrivacyRequestedV1.TOPIC, PrivacyRequests.consumer(service), stages);
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  /** Topics first (best effort, retried by a daemon thread until they exist), then the consumer. */
  static final class Lifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(Lifecycle.class);
    private final EventConsumer consumer;
    private final Producer<String, byte[]> producer;
    private final TopicRegistry topics;
    private final String bootstrap;
    private final AtomicBoolean topicsReady = new AtomicBoolean();
    private volatile boolean running;

    Lifecycle(EventConsumer consumer, Producer<String, byte[]> producer, TopicRegistry topics, String bootstrap) {
      this.consumer = consumer;
      this.producer = producer;
      this.topics = topics;
      this.bootstrap = bootstrap;
    }

    @Override
    public void start() {
      if (!ensureTopics()) {
        Thread t = new Thread(() -> {
          while (running && !ensureTopics()) {
            try {
              Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
          }
        }, "privacy-topics");
        t.setDaemon(true);
        running = true;
        t.start();
      }
      consumer.start();
      running = true;
    }

    private boolean ensureTopics() {
      if (topicsReady.get()) {
        return true;
      }
      try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
          AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
          AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10000))) {
        List<String> created = topics.ensure(admin, Duration.ofSeconds(10));
        topicsReady.set(true);
        log.info("PRIVACY_TOPICS_READY created={}", created);
        return true;
      } catch (RuntimeException e) {
        log.warn("PRIVACY_TOPICS_NOT_READY error={}", e.toString());
        return false;
      }
    }

    @Override
    public void stop() {
      running = false;
      consumer.close();
      producer.close();
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    /** After the service's relay (MAX - 100), with the other consumers. */
    @Override
    public int getPhase() {
      return Integer.MAX_VALUE - 50;
    }
  }
}
