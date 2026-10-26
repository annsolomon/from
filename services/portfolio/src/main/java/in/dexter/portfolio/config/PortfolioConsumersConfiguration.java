package in.dexter.portfolio.config;

import in.dexter.contracts.events.aiparser.StatementParsedV1;
import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.KafkaClients;
import in.dexter.events.consumer.EventConsumer;
import in.dexter.portfolio.app.ProjectionService;
import in.dexter.portfolio.app.StatementService;
import java.time.Clock;
import java.util.List;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Portfolio's event consumers (MASTER_PLAN 6.2, P11), each with retry-1m / retry-10m / DLQ stages:
 * {@code catalog-projection} on {@code dexter.catalog.card.v1}, {@code expense-match} on
 * {@code dexter.ledger.expense.v1} and (P12) {@code statement-parsed} on {@code dexter.ai-parser.statement.v1}.
 * Off unless {@code dexter.portfolio.consumers.enabled=true}; they start after the
 * relay (which creates the topics).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dexter.portfolio.consumers", name = "enabled", havingValue = "true")
public class PortfolioConsumersConfiguration {
  static final String SERVICE = "portfolio";

  @Bean
  SmartLifecycle portfolioConsumers(PortfolioProperties props, ProjectionService projections,
      StatementService statements, EnvelopeCodec codec, Clock clock) {
    String bootstrap = props.consumers().bootstrapServers();
    if (bootstrap == null || bootstrap.isBlank()) {
      throw new IllegalStateException("dexter.portfolio.consumers.bootstrap-servers is required");
    }
    Producer<String, byte[]> producer = KafkaClients.idempotentProducer(bootstrap, SERVICE + "-consumers");
    EventConsumer catalog = new EventConsumer(EventConsumer.Settings.defaults(bootstrap, SERVICE,
        ProjectionService.CATALOG_CONSUMER, List.of(CardPublishedV1.TOPIC)), projections::onCatalogEvent, codec,
        producer, clock);
    EventConsumer expenses = new EventConsumer(EventConsumer.Settings.defaults(bootstrap, SERVICE,
        ProjectionService.EXPENSE_CONSUMER, List.of(ExpenseChangedV1.TOPIC)), projections::onExpenseEvent, codec,
        producer, clock);
    EventConsumer parsed = new EventConsumer(EventConsumer.Settings.defaults(bootstrap, SERVICE,
        StatementService.PARSED_CONSUMER, List.of(StatementParsedV1.TOPIC)), statements::onParsed, codec, producer,
        clock);
    return new Consumers(List.of(catalog, expenses, parsed), producer);
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
