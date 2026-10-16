package in.dexter.testkit.containers;

import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.testcontainers.kafka.KafkaContainer;

/** One Kafka (KRaft, the same {@code apache/kafka} image as compose) per test JVM. */
public final class TestKafka {
  public static final String IMAGE = "apache/kafka:4.1.0";
  private static KafkaContainer kafka;

  private TestKafka() {}

  public static synchronized KafkaContainer container() {
    if (kafka == null) {
      kafka = new KafkaContainer(IMAGE);
      kafka.start();
    }
    return kafka;
  }

  public static String bootstrapServers() {
    return container().getBootstrapServers();
  }

  public static Admin admin() {
    return Admin.create(Map.<String, Object>of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()));
  }
}
