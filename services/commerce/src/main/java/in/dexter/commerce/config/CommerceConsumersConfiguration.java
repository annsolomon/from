package in.dexter.commerce.config;

import in.dexter.commerce.app.ProjectionService;
import in.dexter.contracts.events.billing.EntitlementsChangedV1;
import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.portfolio.CardChangedV1;
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
 * Commerce's three projection consumers (MASTER_PLAN 6.2, P17), each with retry-1m / retry-10m / DLQ stages:
 * {@code catalog-cards} on the compacted {@code dexter.catalog.card.v1}, {@code held-cards} on
 * {@code dexter.portfolio.card.v1} and {@code ad-free} on {@code dexter.billing.entitlements.v1}.
 * Off unless {@code dexter.commerce.consumers.enabled=true}; they start after the relay (which creates the topics).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dexter.commerce.consumers", name = "enabled", havingValue = "true")
public class CommerceConsumersConfiguration {
  static final String SERVICE = "commerce";

  @Bean
  SmartLifecycle commerceConsumers(CommerceProperties props, ProjectionService projections, EnvelopeCodec codec,
      Clock clock) {
    String bootstrap = props.consumers().bootstrapServers();
    if (bootstrap == null || bootstrap.isBlank()) {
      throw new IllegalStateException("dexter.commerce.consumers.bootstrap-servers is required");
    }
    Producer<String, byte[]> producer = KafkaClients.idempotentProducer(bootstrap, SERVICE + "-consumers");
    EventConsumer catalog = new EventConsumer(EventConsumer.Settings.defaults(bootstrap, SERVICE,
        ProjectionService.CATALOG_CONSUMER, List.of(CardPublishedV1.TOPIC)), projections::onCatalogEvent, codec,
        producer, clock);
    EventConsumer cards = new EventConsumer(EventConsumer.Settings.defaults(bootstrap, SERVICE,
        ProjectionService.CARD_CONSUMER, List.of(CardChangedV1.TOPIC)), projections::onCardEvent, codec, producer,
        clock);
    EventConsumer entitlements = new EventConsumer(EventConsumer.Settings.defaults(bootstrap, SERVICE,
        ProjectionService.ENTITLEMENTS_CONSUMER, List.of(EntitlementsChangedV1.TOPIC)),
        projections::onEntitlementsEvent, codec, producer, clock);
    return new Consumers(List.of(catalog, cards, entitlements), producer);
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
