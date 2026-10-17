package in.dexter.gateway.infra;

import in.dexter.contracts.events.gateway.ActivityRecordedV1;
import in.dexter.core.id.Ids;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Activity events (MASTER_PLAN P06 step 4): sampled, asynchronous, never blocking. {@link #publish} only offers to a
 * bounded queue served by one daemon thread (a full queue drops the event and counts it); that thread owns the Kafka
 * producer (max.block.ms bounds a missing-metadata wait). The gateway has no database, so there is no outbox:
 * activity is analytics, losing a sample is acceptable. The topic is created on the first send attempt (Kafka
 * auto-create is off, MASTER_PLAN 6.2) and retried until it exists. Envelope = dexter-events EventEnvelope v1 JSON.
 */
public final class ActivityPublisher implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(ActivityPublisher.class);
  private final ThreadPoolExecutor worker;
  private final String bootstrap;
  private final String topic;
  private final int partitions;
  private final short replication;
  private final JsonMapper json;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong sent = new AtomicLong();
  private final AtomicBoolean topicReady = new AtomicBoolean();
  private volatile long nextTopicAttempt;
  private Producer<String, byte[]> producer;

  public ActivityPublisher(String bootstrap, String topic, int partitions, short replication, int queueCapacity,
      JsonMapper json) {
    this.bootstrap = bootstrap;
    this.topic = topic;
    this.partitions = partitions;
    this.replication = replication;
    this.json = json;
    this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
        r -> {
          Thread t = new Thread(r, "gateway-activity");
          t.setDaemon(true);
          return t;
        }, (r, ex) -> dropped.incrementAndGet());
  }

  /** Never blocks the caller. */
  public void publish(UUID accountId, ActivityRecordedV1 data) {
    worker.execute(() -> send(accountId, data));
  }

  public long dropped() {
    return dropped.get();
  }

  public long sent() {
    return sent.get();
  }

  private void send(UUID accountId, ActivityRecordedV1 data) {
    try {
      ensureTopic();
      UUID id = Ids.newId();
      Map<String, Object> env = new LinkedHashMap<>();
      env.put("id", id.toString());
      env.put("type", ActivityRecordedV1.TYPE);
      env.put("version", ActivityRecordedV1.VERSION);
      env.put("source", "gateway");
      env.put("time", Instant.now().toString());
      env.put("accountId", accountId == null ? null : accountId.toString());
      env.put("aggregateId", id.toString());
      env.put("aggregateVersion", 1);
      env.put("traceparent", null);
      env.put("data", data);
      byte[] value = json.writeValueAsBytes(env);
      String key = accountId != null ? accountId.toString() : data.requestId();
      producer().send(new ProducerRecord<>(topic, key, value), (md, e) -> {
        if (e != null) {
          dropped.incrementAndGet();
        } else {
          sent.incrementAndGet();
        }
      });
    } catch (RuntimeException e) {
      dropped.incrementAndGet();
      log.debug("GATEWAY_ACTIVITY_DROPPED {}", e.toString());
    }
  }

  private Producer<String, byte[]> producer() {
    if (producer == null) {
      Map<String, Object> c = new LinkedHashMap<>();
      c.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
      c.put(ProducerConfig.CLIENT_ID_CONFIG, "gateway-activity");
      c.put(ProducerConfig.ACKS_CONFIG, "1");
      c.put(ProducerConfig.LINGER_MS_CONFIG, 50);
      c.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500);
      c.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
      c.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
      c.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 8L * 1024 * 1024);
      c.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
      producer = new KafkaProducer<>(c, new StringSerializer(), new ByteArraySerializer());
    }
    return producer;
  }

  private void ensureTopic() {
    if (topicReady.get() || System.currentTimeMillis() < nextTopicAttempt) {
      return;
    }
    nextTopicAttempt = System.currentTimeMillis() + Duration.ofMinutes(1).toMillis();
    try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
        AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000, AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000))) {
      admin.createTopics(List.of(new NewTopic(topic, partitions, replication))).all().get(5, TimeUnit.SECONDS);
      topicReady.set(true);
      log.info("GATEWAY_ACTIVITY_TOPIC_CREATED topic={}", topic);
    } catch (java.util.concurrent.ExecutionException e) {
      if (e.getCause() instanceof TopicExistsException) {
        topicReady.set(true);
      } else {
        log.warn("GATEWAY_ACTIVITY_TOPIC_FAILED topic={} error={}", topic, e.getCause().toString());
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn("GATEWAY_ACTIVITY_TOPIC_FAILED topic={} error={}", topic, e.toString());
    }
  }

  @Override
  public void close() {
    worker.shutdown();
    try {
      worker.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (producer != null) {
      producer.close(Duration.ofSeconds(2));
    }
  }
}
