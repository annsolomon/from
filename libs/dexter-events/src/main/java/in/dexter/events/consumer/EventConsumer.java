package in.dexter.events.consumer;

import in.dexter.events.EnvelopeCodec;
import in.dexter.events.EventEnvelope;
import in.dexter.events.Topics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A consumer with non-blocking retries (MASTER_PLAN 6.2, ADR-0006). One loop per stage, each with its
 * own {@link KafkaConsumer} on a virtual thread:
 * <ul>
 *   <li><b>main</b> reads the source topics (group {@code <service>.<consumer>});</li>
 *   <li>a failed event is forwarded to {@code <topic>.<consumer>.retry-1m}, then {@code retry-10m},
 *       then {@code <topic>.<consumer>.dlq}, with headers {@code dexter-attempt},
 *       {@code dexter-not-before}, {@code dexter-original-topic}, {@code dexter-error};</li>
 *   <li>a retry stage pauses a partition until its head message is due (messages in one stage are
 *       FIFO with the same delay, so the head is always the earliest);</li>
 *   <li>a message that is not a valid envelope (poison) goes straight to the DLQ.</li>
 * </ul>
 * Offsets are committed only after the handler succeeded or the message was forwarded, so delivery
 * is at-least-once; handlers make it exactly-once in effect with
 * {@link in.dexter.events.InboxGuard} in their transaction. Retries break per-key ordering for the
 * failed event only; projections guard with {@link in.dexter.events.ProjectionVersionGuard}.
 */
public final class EventConsumer implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);
  public static final String H_ATTEMPT = "dexter-attempt";
  public static final String H_NOT_BEFORE = "dexter-not-before";
  public static final String H_ORIGINAL_TOPIC = "dexter-original-topic";
  public static final String H_ERROR = "dexter-error";
  public static final String H_CONSUMER = "dexter-consumer";

  /** Handles one event. Throwing sends it to the next retry stage (then the DLQ). */
  @FunctionalInterface
  public interface Handler {
    void handle(EventEnvelope event) throws Exception;
  }

  /** A retry stage: its topic suffix (for example {@code retry-1m}) and delay. */
  public record Stage(String name, Duration delay) {
    public Stage {
      Topics.retry("x", "x", name);
      if (delay.isNegative()) {
        throw new IllegalArgumentException("delay must be >= 0");
      }
    }
  }

  public record Settings(String bootstrapServers, String service, String consumer, List<String> topics,
      List<Stage> stages, int maxPollRecords, Duration pollTimeout) {
    public Settings {
      Objects.requireNonNull(bootstrapServers, "bootstrapServers");
      Topics.name(service);
      Topics.name(consumer);
      topics = List.copyOf(topics);
      stages = List.copyOf(stages);
      if (topics.isEmpty()) {
        throw new IllegalArgumentException("at least one topic");
      }
    }

    /** MASTER_PLAN 6.2 stages: 1 minute, then 10 minutes, then the DLQ. */
    public static Settings defaults(String bootstrapServers, String service, String consumer, List<String> topics) {
      return new Settings(bootstrapServers, service, consumer, topics,
          List.of(new Stage("retry-1m", Duration.ofMinutes(1)), new Stage("retry-10m", Duration.ofMinutes(10))),
          200, Duration.ofMillis(500));
    }

    public String groupId(int stage) {
      return service + "." + consumer + (stage == 0 ? "" : "." + stages.get(stage - 1).name());
    }

    public List<String> stageNames() {
      return stages.stream().map(Stage::name).toList();
    }
  }

  private final Settings s;
  private final Handler handler;
  private final EnvelopeCodec codec;
  private final Producer<String, byte[]> producer;
  private final Clock clock;
  private final List<KafkaConsumer<String, byte[]>> consumers = new CopyOnWriteArrayList<>();
  private final List<Thread> threads = new ArrayList<>();
  private volatile boolean running;

  public EventConsumer(Settings settings, Handler handler, EnvelopeCodec codec, Producer<String, byte[]> producer,
      Clock clock) {
    this.s = Objects.requireNonNull(settings, "settings");
    this.handler = Objects.requireNonNull(handler, "handler");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.producer = Objects.requireNonNull(producer, "producer");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    for (int stage = 0; stage <= s.stages().size(); stage++) {
      final int st = stage;
      List<String> topics = stage == 0 ? s.topics()
          : s.topics().stream().map(t -> Topics.retry(t, s.consumer(), s.stages().get(st - 1).name())).toList();
      KafkaConsumer<String, byte[]> kc = new KafkaConsumer<>(consumerConfig(s.groupId(stage)),
          new StringDeserializer(), new ByteArrayDeserializer());
      consumers.add(kc);
      threads.add(Thread.ofVirtual().name("consumer-" + s.groupId(stage)).start(() -> run(kc, topics, st)));
    }
  }

  @Override
  public void close() {
    synchronized (this) {
      if (!running) {
        return;
      }
      running = false;
    }
    consumers.forEach(KafkaConsumer::wakeup);
    for (Thread t : threads) {
      try {
        t.join(Duration.ofSeconds(15).toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    threads.clear();
    consumers.clear();
  }

  private Map<String, Object> consumerConfig(String groupId) {
    Map<String, Object> c = new HashMap<>();
    c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, s.bootstrapServers());
    c.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    c.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    c.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, s.maxPollRecords());
    c.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    return c;
  }

  private void run(KafkaConsumer<String, byte[]> kc, List<String> topics, int stage) {
    Map<TopicPartition, Long> pausedUntil = new HashMap<>();
    try {
      kc.subscribe(topics);
      while (running) {
        resumeDue(kc, pausedUntil);
        ConsumerRecords<String, byte[]> records = kc.poll(s.pollTimeout());
        Map<TopicPartition, OffsetAndMetadata> commit = new HashMap<>();
        boolean backOff = false;
        for (TopicPartition tp : records.partitions()) {
          for (ConsumerRecord<String, byte[]> r : records.records(tp)) {
            if (stage > 0) {
              long due = longHeader(r, H_NOT_BEFORE, 0);
              long now = clock.millis();
              if (due > now) {
                kc.seek(tp, r.offset());
                kc.pause(List.of(tp));
                pausedUntil.put(tp, due);
                break;
              }
            }
            try {
              process(r, stage);
            } catch (RuntimeException infra) {
              // Kafka could not take the forward: re-read this record after a pause, commit nothing past it.
              log.error("consumer {}: {} (will re-read {}-{}@{})", s.groupId(stage), infra.toString(), r.topic(),
                  r.partition(), r.offset());
              kc.seek(tp, r.offset());
              backOff = true;
              break;
            }
            commit.put(tp, new OffsetAndMetadata(r.offset() + 1));
          }
        }
        if (!commit.isEmpty()) {
          kc.commitSync(commit);
        }
        if (backOff) {
          Thread.sleep(1000);
        }
      }
    } catch (WakeupException e) {
      if (running) {
        log.error("consumer {} woken unexpectedly", s.groupId(stage), e);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      log.error("consumer {} stopped: {}", s.groupId(stage), e.toString(), e);
    } finally {
      try {
        kc.close(Duration.ofSeconds(5));
      } catch (RuntimeException ignored) {
        // closing anyway
      }
    }
  }

  private void resumeDue(KafkaConsumer<String, byte[]> kc, Map<TopicPartition, Long> pausedUntil) {
    if (pausedUntil.isEmpty()) {
      return;
    }
    long now = clock.millis();
    List<TopicPartition> resume = new ArrayList<>();
    Iterator<Map.Entry<TopicPartition, Long>> it = pausedUntil.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<TopicPartition, Long> e = it.next();
      if (!kc.assignment().contains(e.getKey())) {
        it.remove();
      } else if (e.getValue() <= now) {
        resume.add(e.getKey());
        it.remove();
      }
    }
    if (!resume.isEmpty()) {
      kc.resume(resume);
    }
  }

  private void process(ConsumerRecord<String, byte[]> r, int stage) {
    String original = stage == 0 ? r.topic() : textHeader(r, H_ORIGINAL_TOPIC, r.topic());
    EventEnvelope e;
    try {
      e = codec.decode(r.value());
    } catch (IllegalArgumentException poison) {
      log.warn("consumer {}: poison message at {}-{}@{} → DLQ ({})", s.consumer(), r.topic(), r.partition(),
          r.offset(), poison.getMessage());
      forward(r, Topics.dlq(original, s.consumer()), original, stage + 1, 0, poison);
      return;
    }
    try {
      handler.handle(e);
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      int attempt = stage + 1;
      if (stage < s.stages().size()) {
        Stage next = s.stages().get(stage);
        log.warn("consumer {}: event {} ({}) failed attempt {} → {}: {}", s.consumer(), e.id(), e.type(), attempt,
            next.name(), failure.toString());
        forward(r, Topics.retry(original, s.consumer(), next.name()), original, attempt,
            clock.millis() + next.delay().toMillis(), failure);
      } else {
        log.error("consumer {}: event {} ({}) failed {} attempts → DLQ: {}", s.consumer(), e.id(), e.type(), attempt,
            failure.toString());
        forward(r, Topics.dlq(original, s.consumer()), original, attempt, 0, failure);
      }
    }
  }

  /** Synchronous send: the offset is committed only once the forward is durable. */
  private void forward(ConsumerRecord<String, byte[]> r, String target, String original, int attempt, long notBefore,
      Throwable error) {
    List<Header> headers = new ArrayList<>();
    for (Header h : r.headers()) {
      if (!h.key().startsWith("dexter-")) {
        headers.add(h);
      }
    }
    headers.add(header(H_ORIGINAL_TOPIC, original));
    headers.add(header(H_ATTEMPT, Integer.toString(attempt)));
    headers.add(header(H_NOT_BEFORE, Long.toString(notBefore)));
    headers.add(header(H_CONSUMER, s.consumer()));
    String msg = error.getClass().getSimpleName() + ": " + error.getMessage();
    headers.add(header(H_ERROR, msg.length() > 500 ? msg.substring(0, 500) : msg));
    try {
      producer.send(new ProducerRecord<>(target, null, r.key(), r.value(), headers)).get(30, TimeUnit.SECONDS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted forwarding to " + target, ie);
    } catch (ExecutionException | TimeoutException fe) {
      // Not committed: the loop stops and the message is read again after restart/rebalance.
      throw new IllegalStateException("forwarding to " + target + " failed", fe);
    }
  }

  private static Header header(String k, String v) {
    return new RecordHeader(k, v.getBytes(StandardCharsets.UTF_8));
  }

  static String textHeader(ConsumerRecord<String, byte[]> r, String key, String dflt) {
    Header h = r.headers().lastHeader(key);
    return h == null || h.value() == null ? dflt : new String(h.value(), StandardCharsets.UTF_8);
  }

  static long longHeader(ConsumerRecord<String, byte[]> r, String key, long dflt) {
    try {
      return Long.parseLong(textHeader(r, key, Long.toString(dflt)));
    } catch (NumberFormatException e) {
      return dflt;
    }
  }
}
