package in.dexter.testkit.support;

import in.dexter.events.consumer.EventConsumer;
import in.dexter.events.Topics;
import in.dexter.testkit.containers.TestKafka;
import in.dexter.testkit.demo.DemoEvents;
import java.time.Duration;
import java.util.List;
import org.apache.kafka.clients.admin.Admin;

/** Topic setup for the demo ITs: dev partitions (3), replication 1, short retry delays. */
public final class DemoKafka {
  public static final List<EventConsumer.Stage> FAST_STAGES = List.of(
      new EventConsumer.Stage("retry-1m", Duration.ofMillis(300)),
      new EventConsumer.Stage("retry-10m", Duration.ofMillis(600)));

  private DemoKafka() {}

  /** Creates the demo topic plus the retry/DLQ topics of {@code consumer}. */
  public static void ensureTopics(String consumer) {
    in.dexter.events.topics.TopicRegistry reg = new in.dexter.events.topics.TopicRegistry(3, (short) 1)
        .main("demo", "note")
        .consumer(DemoEvents.TOPIC, consumer, FAST_STAGES.stream().map(EventConsumer.Stage::name).toList());
    try (Admin admin = TestKafka.admin()) {
      reg.ensure(admin, Duration.ofSeconds(30));
    }
  }

  public static String retryTopic(String consumer, String stage) {
    return Topics.retry(DemoEvents.TOPIC, consumer, stage);
  }

  public static String dlq(String consumer) {
    return Topics.dlq(DemoEvents.TOPIC, consumer);
  }
}
