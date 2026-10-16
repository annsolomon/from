package in.dexter.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The CloudEvents-style envelope (MASTER_PLAN 6.1). {@code accountId} is null for global aggregates
 * (then the Kafka key is {@code aggregateId}). {@code aggregateVersion} lets projections drop stale,
 * out-of-order events. JSON form: {@link EnvelopeCodec}.
 */
public record EventEnvelope(UUID id, String type, int version, String source, Instant time,
    @Nullable UUID accountId, UUID aggregateId, long aggregateVersion, @Nullable String traceparent, JsonNode data) {

  public EventEnvelope {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(aggregateId, "aggregateId");
    Objects.requireNonNull(data, "data");
    if (version < 1) {
      throw new IllegalArgumentException("version must be >= 1");
    }
    if (aggregateVersion < 0) {
      throw new IllegalArgumentException("aggregateVersion must be >= 0");
    }
  }

  /** The Kafka message key: per-account ordering, or per-aggregate for global aggregates. */
  public String messageKey() {
    return (accountId != null ? accountId : aggregateId).toString();
  }
}
