package in.dexter.events;

import in.dexter.core.id.Ids;
import in.dexter.db.sql.Sql;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Appends an event to {@code <svc>.outbox} in the <em>caller's</em> transaction (MASTER_PLAN 6.3):
 * commit publishes it (through the relay), rollback discards it. Fails fast when no read-write
 * transaction is active, so an event can never be written outside the state change it describes.
 * The app role may only INSERT into the outbox, so this never reads it back.
 */
public final class OutboxWriter {
  private final JdbcClient jdbc;
  private final EnvelopeCodec codec;
  private final String source;
  private final Clock clock;
  private final Supplier<@Nullable String> traceparent;
  private final String insertSql;

  /**
   * @param schema      the service schema ({@code ledger}, ...)
   * @param source      envelope {@code source} (the service name)
   * @param traceparent current W3C traceparent, or null (P19 wires OpenTelemetry)
   */
  public OutboxWriter(JdbcClient jdbc, EnvelopeCodec codec, String schema, String source, Clock clock,
      Supplier<@Nullable String> traceparent) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.source = Topics.name(source);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.traceparent = traceparent == null ? () -> null : traceparent;
    this.insertSql = "INSERT INTO " + Sql.ident(schema) + ".outbox (id, topic, message_key, event_type, payload, headers)"
        + " VALUES (:id, :topic, :key, :type, CAST(:payload AS jsonb), CAST(:headers AS jsonb))";
  }

  /**
   * Writes one event. {@code accountId} is null only for global aggregates.
   *
   * @return the envelope as written (its {@code id} is the event id consumers dedupe on)
   */
  public <T> EventEnvelope append(EventType<T> type, @Nullable UUID accountId, UUID aggregateId, long aggregateVersion,
      T data) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(aggregateId, "aggregateId");
    if (!type.dataClass().isInstance(data)) {
      throw new IllegalArgumentException("data must be a " + type.dataClass().getSimpleName());
    }
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("OutboxWriter.append needs an active transaction (same tx as the state change)");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("OutboxWriter.append inside a read-only transaction");
    }
    JsonNode dataNode = codec.mapper().valueToTree(data);
    EventEnvelope e = new EventEnvelope(Ids.newId(), type.type(), type.version(), source, clock.instant(), accountId,
        aggregateId, aggregateVersion, traceparent.get(), dataNode);
    ObjectNode headers = codec.mapper().createObjectNode();
    headers.put("ce_id", e.id().toString());
    headers.put("ce_type", e.type());
    headers.put("ce_version", Integer.toString(e.version()));
    if (e.traceparent() != null) {
      headers.put("traceparent", e.traceparent());
    }
    jdbc.sql(insertSql)
        .param("id", e.id())
        .param("topic", type.topic())
        .param("key", e.messageKey())
        .param("type", e.type())
        .param("payload", codec.encode(e))
        .param("headers", codec.mapper().writeValueAsString(headers))
        .update();
    return e;
  }
}
