package in.dexter.events;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Envelope ↔ JSON (MASTER_PLAN 6.1). Written field by field so the wire form never depends on mapper
 * settings (time is always ISO-8601 UTC); read strictly — a missing or mistyped field is a poison
 * message ({@link IllegalArgumentException}), which consumers send straight to the DLQ.
 */
public final class EnvelopeCodec {
  private final ObjectMapper mapper;

  public EnvelopeCodec(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  public ObjectMapper mapper() {
    return mapper;
  }

  public String encode(EventEnvelope e) {
    ObjectNode n = mapper.createObjectNode();
    n.put("id", e.id().toString());
    n.put("type", e.type());
    n.put("version", e.version());
    n.put("source", e.source());
    n.put("time", e.time().toString());
    if (e.accountId() == null) {
      n.putNull("accountId");
    } else {
      n.put("accountId", e.accountId().toString());
    }
    n.put("aggregateId", e.aggregateId().toString());
    n.put("aggregateVersion", e.aggregateVersion());
    if (e.traceparent() == null) {
      n.putNull("traceparent");
    } else {
      n.put("traceparent", e.traceparent());
    }
    n.set("data", e.data());
    return mapper.writeValueAsString(n);
  }

  public EventEnvelope decode(byte[] json) {
    if (json == null) {
      throw new IllegalArgumentException("null message");
    }
    return decode(new String(json, StandardCharsets.UTF_8));
  }

  public EventEnvelope decode(String json) {
    JsonNode n;
    try {
      n = mapper.readTree(json);
    } catch (JacksonException ex) {
      throw new IllegalArgumentException("not JSON", ex);
    }
    if (n == null || !n.isObject()) {
      throw new IllegalArgumentException("envelope must be a JSON object");
    }
    JsonNode data = n.get("data");
    if (data == null || !data.isObject()) {
      throw new IllegalArgumentException("data must be an object");
    }
    return new EventEnvelope(uuid(n, "id", false), text(n, "type", false), (int) integer(n, "version"),
        text(n, "source", false), instant(n, "time"), uuid(n, "accountId", true), uuid(n, "aggregateId", false),
        integer(n, "aggregateVersion"), text(n, "traceparent", true), data);
  }

  /** Typed view of {@code data}. */
  public <T> T data(EventEnvelope e, Class<T> type) {
    try {
      return mapper.treeToValue(e.data(), type);
    } catch (JacksonException ex) {
      throw new IllegalArgumentException("data does not match " + type.getSimpleName(), ex);
    }
  }

  private static String text(JsonNode n, String f, boolean nullable) {
    JsonNode v = n.get(f);
    if (v == null || v.isNull()) {
      if (nullable) {
        return null;
      }
      throw new IllegalArgumentException("missing " + f);
    }
    if (!v.isString()) {
      throw new IllegalArgumentException(f + " must be a string");
    }
    return v.stringValue();
  }

  private static UUID uuid(JsonNode n, String f, boolean nullable) {
    String s = text(n, f, nullable);
    if (s == null) {
      return null;
    }
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(f + " must be a UUID", ex);
    }
  }

  private static long integer(JsonNode n, String f) {
    JsonNode v = n.get(f);
    if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) {
      throw new IllegalArgumentException(f + " must be an integer");
    }
    return v.longValue();
  }

  private static Instant instant(JsonNode n, String f) {
    try {
      return Instant.parse(text(n, f, false));
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException(f + " must be ISO-8601", ex);
    }
  }
}
