package in.dexter.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.Consumer;

/**
 * The event library's meters (MASTER_PLAN P19 step 1), on {@link Metrics#globalRegistry} — Spring Boot adds its
 * Prometheus registry there, so every service exports them without wiring:
 * <ul>
 *   <li>{@code dexter_outbox_lag_millis{schema}} — age of the oldest unpublished outbox row (0 when none), and
 *       {@code dexter_outbox_pending{schema}} — how many; reported by the relay leader (alert: lag > 5 s)</li>
 *   <li>{@code dexter_dlq_messages{consumer,topic}} — messages on a consumer's dead-letter topics (alert: > 0)</li>
 *   <li>{@code dexter_event_handle_seconds{consumer,type,outcome}} — handler latency (histogram)</li>
 *   <li>Kafka's own consumer metrics ({@code kafka_consumer_fetch_manager_records_lag_max}, ...) per client</li>
 * </ul>
 */
public final class EventMetrics {
  private static final Map<String, AtomicLong> GAUGES = new ConcurrentHashMap<>();

  private EventMetrics() {}

  static MeterRegistry registry() {
    return Metrics.globalRegistry;
  }

  /** A long-valued gauge, registered once per name + tags and updated in place. */
  public static AtomicLong gauge(String name, String description, String... tags) {
    String key = name + String.join("|", tags);
    return GAUGES.computeIfAbsent(key, k -> {
      AtomicLong v = new AtomicLong();
      Gauge.builder(name, v, AtomicLong::doubleValue).description(description).tags(tags).register(registry());
      return v;
    });
  }

  public static AtomicLong outboxLagMillis(String schema) {
    return gauge("dexter.outbox.lag.millis", "Age of the oldest unpublished outbox row (ms)", "schema", schema);
  }

  public static AtomicLong outboxPending(String schema) {
    return gauge("dexter.outbox.pending", "Unpublished outbox rows", "schema", schema);
  }

  public static AtomicLong dlqMessages(String consumer, String topic) {
    return gauge("dexter.dlq.messages", "Messages on a consumer's dead-letter topic", "consumer", consumer,
        "topic", topic);
  }

  /** One handled event: {@code dexter.event.handle} timer (histogram buckets configured per service). */
  public static void handled(String consumer, String type, String outcome, long nanos) {
    Timer.builder("dexter.event.handle").description("Event handler latency")
        .tags("consumer", consumer, "type", type, "outcome", outcome).register(registry())
        .record(nanos, TimeUnit.NANOSECONDS);
  }

  /** Kafka's client metrics for one consumer (closed with it). */
  public static AutoCloseable bind(Consumer<?, ?> consumer) {
    KafkaClientMetrics m = new KafkaClientMetrics(consumer);
    m.bindTo(registry());
    return m;
  }
}
