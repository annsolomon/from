package in.dexter.audit.config;

import in.dexter.audit.app.EventIngest;
import in.dexter.audit.app.PrivacyService;
import in.dexter.contracts.events.gateway.ActivityRecordedV1;
import in.dexter.contracts.events.privacy.PrivacyCompletedV1;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.KafkaClients;
import in.dexter.events.consumer.EventConsumer;
import java.time.Clock;
import java.util.List;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Audit's three consumers (MASTER_PLAN 6.2, P18), each with retry-1m / retry-10m / DLQ stages: {@code events}
 * (identity, billing, commerce, planning → audit entries and notifications), {@code activity} (gateway) and
 * {@code privacy-acks} (every participant's {@code dexter.<svc>.privacy.v1}). Off unless
 * {@code dexter.audit.consumers.enabled=true}; they start after the relay (which creates the topics).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dexter.audit.consumers", name = "enabled", havingValue = "true")
public class AuditConsumersConfiguration {
  static final String SERVICE = "audit";

  @Bean
  SmartLifecycle auditConsumers(AuditProperties props, EventIngest ingest, PrivacyService privacy,
      EnvelopeCodec codec, Clock clock) {
    String bootstrap = props.consumers().bootstrapServers();
    if (bootstrap == null || bootstrap.isBlank()) {
      throw new IllegalStateException("dexter.audit.consumers.bootstrap-servers is required");
    }
    Producer<String, byte[]> producer = KafkaClients.idempotentProducer(bootstrap, SERVICE + "-consumers");
    EventConsumer events = new EventConsumer(EventConsumer.Settings.defaults(bootstrap, SERVICE,
        EventIngest.EVENTS_CONSUMER, EventIngest.EVENT_TOPICS), ingest::onEvent, codec, producer, clock);
    EventConsumer activity = new EventConsumer(EventConsumer.Settings.defaults(bootstrap, SERVICE,
        EventIngest.ACTIVITY_CONSUMER, List.of(ActivityRecordedV1.TOPIC)), ingest::onActivity, codec, producer, clock);
    EventConsumer acks = new EventConsumer(EventConsumer.Settings.defaults(bootstrap, SERVICE,
        PrivacyService.ACK_CONSUMER, props.privacy().services().stream().map(PrivacyCompletedV1::topic).toList()),
        privacy::onAck, codec, producer, clock);
    return new Consumers(List.of(events, activity, acks), producer);
  }

  static final class Consumers implements SmartLifecycle {
    private final List<EventConsumer> consumers;
    private final Producer<String, byte[]> producer;
    private volatile boolean running;

    Consumers(List<EventConsumer> consumers, Producer<String, byte[]> producer) {
      this.consumers = consumers;
      this.producer = producer;
    }

    @Override
    public void start() {
      consumers.forEach(EventConsumer::start);
      running = true;
    }

    @Override
    public void stop() {
      consumers.forEach(EventConsumer::close);
      producer.close();
      running = false;
    }

    @Override
    public boolean isRunning() {
      return running;
    }

    @Override
    public int getPhase() {
      return Integer.MAX_VALUE - 50;
    }
  }
}
