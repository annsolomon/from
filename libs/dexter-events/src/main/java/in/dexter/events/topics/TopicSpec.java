package in.dexter.events.topics;

import java.time.Duration;
import java.util.Objects;

/** One topic to exist (MASTER_PLAN 6.2): 12 partitions in prod (dev: 3), 7-day retention, optional compaction. */
public record TopicSpec(String name, int partitions, short replication, Duration retention, boolean compacted) {
  public TopicSpec {
    Objects.requireNonNull(name, "name");
    if (!name.matches("[a-z0-9._-]{1,249}")) {
      throw new IllegalArgumentException("bad topic name: " + name);
    }
    if (partitions < 1 || replication < 1) {
      throw new IllegalArgumentException("partitions and replication must be >= 1");
    }
    Objects.requireNonNull(retention, "retention");
  }
}
