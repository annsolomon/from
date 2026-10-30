package in.dexter.planning.config;

import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.events.EnvelopeCodec;
import in.dexter.events.KafkaClients;
import in.dexter.events.consumer.EventConsumer;
import in.dexter.planning.app.SpendService;
import java.time.Clock;
import java.util.List;
import org.apache.kafka.clients.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Planning's event consumer (MASTER_PLAN 6.2, P13), with retry-1m / retry-10m / DLQ stages:
 * {@code budget-spend} on {@code dexter.ledger.expense.v1}. Off unless
 * {@code dexter.planning.consumers.enabled=true}; it starts after the relay (which creates the topics).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dexter.planning.consumers", name = "enabled", havingValue = "true")
public class PlanningConsumersConfiguration {
  static final String SERVICE = "planning";

  @Bean
  SmartLifecycle planningConsumers(PlanningProperties props, SpendService spend, EnvelopeCodec codec, Clock clock) {
    String bootstrap = props.consumers().bootstrapServers();
    if (bootstrap == null || bootstrap.isBlank()) {
      throw new IllegalStateException("dexter.planning.consumers.bootstrap-servers is required");
    }
    Producer<String, byte[]> producer = KafkaClients.idempotentProducer(bootstrap, SERVICE + "-consumers");
    EventConsumer expenses = new EventConsumer(EventConsumer.Settings.defaults(bootstrap, SERVICE,
        SpendService.CONSUMER, List.of(ExpenseChangedV1.TOPIC)), spend::onExpenseEvent, codec, producer, clock);
    return new Consumers(List.of(expenses), producer);
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
