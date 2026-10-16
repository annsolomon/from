package in.dexter.testkit.support;

import in.dexter.testkit.containers.TestKafka;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Reads a topic from the beginning with a throw-away group, for assertions. */
public final class KafkaProbe {
  private KafkaProbe() {}

  /** Polls until {@code done} holds for the matching records or {@code timeout} passes; returns the matches. */
  public static List<ConsumerRecord<String, byte[]>> read(String topic, Predicate<ConsumerRecord<String, byte[]>> match,
      Predicate<List<ConsumerRecord<String, byte[]>>> done, Duration timeout) {
    Map<String, Object> c = Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestKafka.bootstrapServers(),
        ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID(),
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    List<ConsumerRecord<String, byte[]>> out = new ArrayList<>();
    try (KafkaConsumer<String, byte[]> kc = new KafkaConsumer<>(c, new StringDeserializer(), new ByteArrayDeserializer())) {
      kc.subscribe(List.of(topic));
      long deadline = System.nanoTime() + timeout.toNanos();
      while (System.nanoTime() < deadline && !done.test(out)) {
        for (ConsumerRecord<String, byte[]> r : kc.poll(Duration.ofMillis(250))) {
          if (match.test(r)) {
            out.add(r);
          }
        }
      }
    }
    return out;
  }
}
