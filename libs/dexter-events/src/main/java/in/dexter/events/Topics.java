package in.dexter.events;

import java.util.regex.Pattern;

/**
 * Topic naming (MASTER_PLAN 6.2, ADR-0006). Main topics are {@code dexter.<service>.<aggregate>.v1};
 * retry and dead-letter topics belong to one consumer: {@code <topic>.<consumer>.retry-1m},
 * {@code <topic>.<consumer>.retry-10m}, {@code <topic>.<consumer>.dlq}, so one consumer's failures
 * never replay into another consumer.
 */
public final class Topics {
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");
  private static final Pattern STAGE = Pattern.compile("retry-[0-9]{1,4}[smh]");

  private Topics() {}

  public static String main(String service, String aggregate) {
    return "dexter." + name(service) + "." + name(aggregate) + ".v1";
  }

  public static String retry(String topic, String consumer, String stage) {
    if (!STAGE.matcher(stage).matches()) {
      throw new IllegalArgumentException("bad retry stage: " + stage);
    }
    return topic + "." + name(consumer) + "." + stage;
  }

  public static String dlq(String topic, String consumer) {
    return topic + "." + name(consumer) + ".dlq";
  }

  /** Validates a service, aggregate or consumer name (lower-case, dashes allowed). */
  public static String name(String s) {
    if (s == null || !NAME.matcher(s).matches()) {
      throw new IllegalArgumentException("bad name (lower-case letters, digits, dashes): " + s);
    }
    return s;
  }
}
