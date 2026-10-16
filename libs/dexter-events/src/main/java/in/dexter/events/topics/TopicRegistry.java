package in.dexter.events.topics;

import in.dexter.events.Topics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Declares every topic a service produces or consumes (main, per-consumer retry stages, DLQ) and
 * creates the missing ones at startup (MASTER_PLAN 6.2). Idempotent: existing topics are left alone,
 * so it is safe on every start and on every instance (dev Kafka has no volume, so topics are
 * recreated after a container reset).
 */
public final class TopicRegistry {
  private static final Logger log = LoggerFactory.getLogger(TopicRegistry.class);
  public static final Duration RETENTION = Duration.ofDays(7);

  private final int partitions;
  private final short replication;
  private final Map<String, TopicSpec> specs = new LinkedHashMap<>();

  /** @param partitions 12 in prod, 3 in dev; @param replication 3 in prod, 1 in dev */
  public TopicRegistry(int partitions, short replication) {
    this.partitions = partitions;
    this.replication = replication;
  }

  public TopicRegistry main(String service, String aggregate) {
    return add(new TopicSpec(Topics.main(service, aggregate), partitions, replication, RETENTION, false));
  }

  /** A compacted projection topic (for example the catalog snapshot). */
  public TopicRegistry compacted(String topic) {
    return add(new TopicSpec(topic, partitions, replication, RETENTION, true));
  }

  /** The retry stages and DLQ of one consumer of {@code topic}. */
  public TopicRegistry consumer(String topic, String consumer, Collection<String> stageNames) {
    for (String stage : stageNames) {
      add(new TopicSpec(Topics.retry(topic, consumer, stage), partitions, replication, RETENTION, false));
    }
    return add(new TopicSpec(Topics.dlq(topic, consumer), partitions, replication, Duration.ofDays(14), false));
  }

  public TopicRegistry add(TopicSpec spec) {
    TopicSpec prev = specs.putIfAbsent(spec.name(), spec);
    if (prev != null && !prev.equals(spec)) {
      throw new IllegalArgumentException("topic declared twice with different settings: " + spec.name());
    }
    return this;
  }

  public List<TopicSpec> specs() {
    return List.copyOf(specs.values());
  }

  /** Creates the missing topics. Returns the names created. */
  public List<String> ensure(Admin admin, Duration timeout) {
    try {
      Set<String> existing = admin.listTopics().names().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      List<NewTopic> missing = new ArrayList<>();
      for (TopicSpec s : specs.values()) {
        if (!existing.contains(s.name())) {
          Map<String, String> cfg = new LinkedHashMap<>();
          cfg.put("retention.ms", Long.toString(s.retention().toMillis()));
          cfg.put("cleanup.policy", s.compacted() ? "compact" : "delete");
          missing.add(new NewTopic(s.name(), s.partitions(), s.replication()).configs(cfg));
        }
      }
      if (missing.isEmpty()) {
        return List.of();
      }
      List<String> created = new ArrayList<>();
      admin.createTopics(missing).values().forEach((name, f) -> {
        try {
          f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
          created.add(name);
        } catch (ExecutionException e) {
          if (!(e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
            throw new IllegalStateException("creating topic " + name + " failed", e.getCause());
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        } catch (TimeoutException e) {
          throw new IllegalStateException("creating topic " + name + " timed out", e);
        }
      });
      log.info("created {} topic(s): {}", created.size(), created);
      return created;
    } catch (ExecutionException e) {
      throw new IllegalStateException("listing topics failed", e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (TimeoutException e) {
      throw new IllegalStateException("Kafka not reachable within " + timeout, e);
    }
  }
}
