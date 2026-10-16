package in.dexter.events;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Kafka client factories with the settings MASTER_PLAN 6.3 requires. */
public final class KafkaClients {
  private KafkaClients() {}

  /** Idempotent producer: {@code enable.idempotence=true}, {@code acks=all}, ordered retries. */
  public static Producer<String, byte[]> idempotentProducer(String bootstrapServers, String clientId) {
    Map<String, Object> c = new HashMap<>();
    c.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    c.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
    c.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    c.put(ProducerConfig.ACKS_CONFIG, "all");
    c.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    c.put(ProducerConfig.LINGER_MS_CONFIG, 5);
    c.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000);
    c.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
    return new KafkaProducer<>(c, new StringSerializer(), new ByteArraySerializer());
  }
}
