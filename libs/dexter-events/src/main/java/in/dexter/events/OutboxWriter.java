package in.dexter.events;

import in.dexter.core.id.Ids;
import in.dexter.db.sql.Sql;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  private final String insertHead;

  /** Rows per multi-row INSERT in {@link #appendAll} (6 parameters each, far below the 65 535 bind limit). */
  public static final int BATCH_ROWS = 500;

  /** One event of a batch: see {@link #appendAll}. */
  public record Pending<T>(@Nullable UUID accountId, UUID aggregateId, long aggregateVersion, T data) {}

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
    this.insertHead = "INSERT INTO " + Sql.ident(schema) + ".outbox (id, topic, message_key, event_type, payload, headers)"
        + " VALUES ";
    this.insertSql = insertHead + "(:id, :topic, :key, :type, CAST(:payload AS jsonb), CAST(:headers AS jsonb))";
  }

  /**
   * Writes one event. {@code accountId} is null only for global aggregates.
   *
   * @return the envelope as written (its {@code id} is the event id consumers dedupe on)
   */
  public <T> EventEnvelope append(EventType<T> type, @Nullable UUID accountId, UUID aggregateId, long aggregateVersion,
      T data) {
    requireWritableTx();
    Prepared p = prepare(type, accountId, aggregateId, aggregateVersion, data);
    jdbc.sql(insertSql)
        .param("id", p.envelope().id())
        .param("topic", type.topic())
        .param("key", p.envelope().messageKey())
        .param("type", p.envelope().type())
        .param("payload", p.payload())
        .param("headers", p.headers())
        .update();
    return p.envelope();
  }

  /**
   * Writes many events of one type with multi-row INSERTs ({@link #BATCH_ROWS} per statement), in the caller's
   * transaction like {@link #append}. Bulk writes (MASTER_PLAN P08: bulk create, CSV import) use it so N events cost
   * N/500 round trips instead of N.
   *
   * @return the envelopes as written, in input order
   */
  public <T> List<EventEnvelope> appendAll(EventType<T> type, List<Pending<T>> events) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(events, "events");
    if (events.isEmpty()) {
      return List.of();
    }
    requireWritableTx();
    List<Prepared> prepared = new ArrayList<>(events.size());
    for (Pending<T> e : events) {
      prepared.add(prepare(type, e.accountId(), e.aggregateId(), e.aggregateVersion(), e.data()));
    }
    List<EventEnvelope> out = new ArrayList<>(prepared.size());
    for (int from = 0; from < prepared.size(); from += BATCH_ROWS) {
      List<Prepared> chunk = prepared.subList(from, Math.min(prepared.size(), from + BATCH_ROWS));
      StringBuilder sql = new StringBuilder(insertHead);
      Map<String, Object> params = new HashMap<>();
      for (int i = 0; i < chunk.size(); i++) {
        Prepared p = chunk.get(i);
        if (i > 0) {
          sql.append(", ");
        }
        sql.append("(:id").append(i).append(", :topic").append(i).append(", :key").append(i).append(", :type")
            .append(i).append(", CAST(:payload").append(i).append(" AS jsonb), CAST(:headers").append(i)
            .append(" AS jsonb))");
        params.put("id" + i, p.envelope().id());
        params.put("topic" + i, type.topic());
        params.put("key" + i, p.envelope().messageKey());
        params.put("type" + i, p.envelope().type());
        params.put("payload" + i, p.payload());
        params.put("headers" + i, p.headers());
        out.add(p.envelope());
      }
      jdbc.sql(sql.toString()).params(params).update();
    }
    return out;
  }

  private record Prepared(EventEnvelope envelope, String payload, String headers) {}

  private <T> Prepared prepare(EventType<T> type, @Nullable UUID accountId, UUID aggregateId, long aggregateVersion,
      T data) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(aggregateId, "aggregateId");
    if (!type.dataClass().isInstance(data)) {
      throw new IllegalArgumentException("data must be a " + type.dataClass().getSimpleName());
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
    return new Prepared(e, codec.encode(e), codec.mapper().writeValueAsString(headers));
  }

  private static void requireWritableTx() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("OutboxWriter.append needs an active transaction (same tx as the state change)");
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("OutboxWriter.append inside a read-only transaction");
    }
  }
}
