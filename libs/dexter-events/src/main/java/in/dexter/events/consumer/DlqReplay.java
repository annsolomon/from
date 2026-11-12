package in.dexter.events.consumer;

import in.dexter.events.KafkaClients;
import in.dexter.events.Topics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * DLQ replay (P19, docs/runbooks/dlq-replay.md): moves the messages on one consumer's dead-letter topic back onto
 * that consumer's first retry topic, due immediately, then deletes them from the DLQ so the
 * {@code dexter_dlq_messages} gauge (and the DexterDeadLetters alert) drops back to what is left.
 *
 * <p>Order: every message is re-published (synchronously, idempotent producer) before anything is deleted, and only
 * up to the end offsets read when the replay started. A crash between the two re-publishes on the next run; that
 * is safe because consumers deduplicate on the event id (inbox), and a message that failed was never recorded.
 * Replay into the retry topic, not the main topic: the main topic is shared by every consumer of it, the retry
 * topic belongs to this consumer only (ADR-0006).
 */
public final class DlqReplay {
  public static final String H_REPLAYED = "dexter-replayed";

  /** What a replay did; {@code kept} = messages the filter left on the DLQ (they and everything after stay). */
  public record Result(String dlq, String target, int replayed, int kept, Map<Integer, Long> truncatedTo) {}

  private DlqReplay() {}

  public static Result replay(String bootstrap, Producer<String, byte[]> producer, String topic, String consumer,
      String stage, Predicate<ConsumerRecord<String, byte[]>> filter, boolean dryRun) throws Exception {
    String dlq = Topics.dlq(topic, consumer);
    String target = Topics.retry(topic, consumer, stage);
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    p.put(ConsumerConfig.GROUP_ID_CONFIG, consumer + ".dlq-replay-" + UUID.randomUUID());
    p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
    p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
    Map<TopicPartition, Long> deleteBefore = new HashMap<>();
    int replayed = 0;
    int kept = 0;
    try (KafkaConsumer<String, byte[]> kc =
        new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
      List<TopicPartition> parts = kc.partitionsFor(dlq, Duration.ofSeconds(10)).stream()
          .map(pi -> new TopicPartition(pi.topic(), pi.partition())).toList();
      kc.assign(parts);
      kc.seekToBeginning(parts);
      Map<TopicPartition, Long> end = kc.endOffsets(parts, Duration.ofSeconds(10));
      Map<TopicPartition, Boolean> blocked = new HashMap<>();
      long deadline = System.currentTimeMillis() + 120_000;
      while (parts.stream().anyMatch(tp -> kc.position(tp) < end.get(tp))) {
        if (System.currentTimeMillis() > deadline) {
          throw new IllegalStateException("DLQ " + dlq + " not read to its end within 120 s");
        }
        for (ConsumerRecord<String, byte[]> r : kc.poll(Duration.ofMillis(500))) {
          TopicPartition tp = new TopicPartition(r.topic(), r.partition());
          if (r.offset() >= end.get(tp)) {
            continue;
          }
          if (!filter.test(r)) {
            kept++;
            blocked.put(tp, true); // nothing at or after a kept message is deleted on this partition
            continue;
          }
          if (!dryRun) {
            producer.send(new ProducerRecord<>(target, null, r.key(), r.value(), headers(r))).get();
          }
          replayed++;
          if (!blocked.getOrDefault(tp, false)) {
            deleteBefore.put(tp, r.offset() + 1);
          }
        }
      }
    }
    if (!dryRun && !deleteBefore.isEmpty()) {
      Properties a = new Properties();
      a.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
      try (Admin admin = Admin.create(a)) {
        Map<TopicPartition, RecordsToDelete> del = new HashMap<>();
        deleteBefore.forEach((tp, off) -> del.put(tp, RecordsToDelete.beforeOffset(off)));
        admin.deleteRecords(del).all().get();
      }
    }
    Map<Integer, Long> truncated = new HashMap<>();
    deleteBefore.forEach((tp, off) -> truncated.put(tp.partition(), dryRun ? 0L : off));
    return new Result(dlq, target, replayed, kept, truncated);
  }

  /** The consumer's own headers minus the attempt bookkeeping; due now, attempt 1, replay counter + 1. */
  static List<Header> headers(ConsumerRecord<String, byte[]> r) {
    List<Header> out = new ArrayList<>();
    int replays = 0;
    String original = r.topic();
    for (Header h : r.headers()) {
      switch (h.key()) {
        case EventConsumer.H_ATTEMPT, EventConsumer.H_NOT_BEFORE -> { }
        case H_REPLAYED -> replays = Integer.parseInt(new String(h.value(), StandardCharsets.UTF_8));
        case EventConsumer.H_ORIGINAL_TOPIC -> original = new String(h.value(), StandardCharsets.UTF_8);
        default -> out.add(h);
      }
    }
    out.add(text(EventConsumer.H_ORIGINAL_TOPIC, original));
    out.add(text(EventConsumer.H_ATTEMPT, "1"));
    out.add(text(EventConsumer.H_NOT_BEFORE, "0"));
    out.add(text(H_REPLAYED, Integer.toString(replays + 1)));
    return out;
  }

  private static Header text(String k, String v) {
    return new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * {@code DlqReplay --bootstrap host:port --topic dexter.ledger.expense.v1 --consumer reco-ledger
   * [--stage retry-1m] [--key <message key>] [--dry-run]} — run with a service jar on the class path
   * (scripts/dlq-replay.sh).
   */
  public static void main(String[] args) throws Exception {
    Map<String, String> a = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("--dry-run")) {
        a.put("dry-run", "true");
      } else if (args[i].startsWith("--") && i + 1 < args.length) {
        a.put(args[i].substring(2), args[++i]);
      } else {
        throw new IllegalArgumentException("unexpected argument: " + args[i]);
      }
    }
    for (String k : List.of("bootstrap", "topic", "consumer")) {
      if (!a.containsKey(k)) {
        System.err.println("usage: DlqReplay --bootstrap B --topic T --consumer C [--stage retry-1m] [--key K]"
            + " [--dry-run]");
        System.exit(2);
      }
    }
    String key = a.get("key");
    try (Producer<String, byte[]> producer = KafkaClients.idempotentProducer(a.get("bootstrap"), "dlq-replay")) {
      Result r = replay(a.get("bootstrap"), producer, a.get("topic"), a.get("consumer"),
          a.getOrDefault("stage", "retry-1m"), rec -> key == null || key.equals(rec.key()),
          a.containsKey("dry-run"));
      System.out.printf("{\"dlq\":\"%s\",\"target\":\"%s\",\"replayed\":%d,\"kept\":%d,\"dryRun\":%s,"
          + "\"truncatedTo\":\"%s\"}%n", r.dlq(), r.target(), r.replayed(), r.kept(), a.containsKey("dry-run"),
          r.truncatedTo());
    }
  }
}
