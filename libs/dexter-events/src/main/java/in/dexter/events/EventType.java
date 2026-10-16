package in.dexter.events;

import java.util.Objects;

/**
 * A published event type (MASTER_PLAN 6.1): {@code type} is {@code <service>.<aggregate>.<action>},
 * {@code version} its schema version ({@code contracts/events/<type>.v<version>.json}), {@code topic}
 * the aggregate's topic, {@code dataClass} the generated/hand-written record for {@code data}.
 */
public record EventType<T>(String type, int version, String topic, Class<T> dataClass) {
  public EventType {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(dataClass, "dataClass");
    if (!type.matches("[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*")) {
      throw new IllegalArgumentException("type must be <service>.<aggregate>.<action>: " + type);
    }
    if (version < 1) {
      throw new IllegalArgumentException("version must be >= 1");
    }
  }

  public static <T> EventType<T> of(String service, String aggregate, String action, int version, Class<T> dataClass) {
    return new EventType<>(Topics.name(service) + "." + Topics.name(aggregate) + "." + Topics.name(action), version,
        Topics.main(service, aggregate), dataClass);
  }

  /** The service part of {@link #type()}; also the envelope {@code source}. */
  public String service() {
    return type.substring(0, type.indexOf('.'));
  }
}
