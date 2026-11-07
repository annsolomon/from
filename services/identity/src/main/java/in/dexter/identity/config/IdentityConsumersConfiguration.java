package in.dexter.identity.config;

import in.dexter.contracts.events.billing.EntitlementsChangedV1;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.KafkaClients;
import in.dexter.events.consumer.EventConsumer;
import in.dexter.identity.app.TierService;
import java.time.Clock;
import java.util.List;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Identity's event consumer (MASTER_PLAN 6.2, P16), with retry-1m / retry-10m / DLQ stages: {@code billing-tier} on
 * {@code dexter.billing.entitlements.v1}, which keeps the tier that goes into the next token. Off unless
 * {@code dexter.identity.consumers.enabled=true}; it starts after the relay (which creates the topics, A-67).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dexter.identity.consumers", name = "enabled", havingValue = "true")
public class IdentityConsumersConfiguration {
  static final String SERVICE = "identity";

  @Bean
  SmartLifecycle identityConsumers(IdentityProperties props, TierService tier, EnvelopeCodec codec, Clock clock) {
    String bootstrap = props.consumers().bootstrapServers();
    if (bootstrap == null || bootstrap.isBlank()) {
      throw new IllegalStateException("dexter.identity.consumers.bootstrap-servers is required");
    }
    Producer<String, byte[]> producer = KafkaClients.idempotentProducer(bootstrap, SERVICE + "-consumers");
    EventConsumer tiers = new EventConsumer(EventConsumer.Settings.defaults(bootstrap, SERVICE,
        TierService.CONSUMER, List.of(EntitlementsChangedV1.TOPIC)), tier::onEntitlementsChanged, codec, producer, clock);
    return new Consumers(List.of(tiers), producer);
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

    /** After the relay (MAX - 100), so the topics exist. */
    @Override
    public int getPhase() {
      return Integer.MAX_VALUE - 50;
    }
  }
}
