package in.dexter.seed;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * A deterministic JSON writer for the manifest and the trail fixtures: maps keep insertion order, instants are UTC
 * ISO-8601 at millisecond precision, no floating point. Small on purpose so the bytes never depend on a library's
 * defaults (A-16 reasoning).
 */
public final class Json {
  private static final DateTimeFormatter MILLIS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);

  private Json() {}

  public static String write(Object v) {
    StringBuilder sb = new StringBuilder();
    value(v, sb, 0, true);
    return sb.append('\n').toString();
  }

  /** Pretty for the top level and its direct children, compact below (one fixture row per line). */
  private static void value(Object v, StringBuilder sb, int depth, boolean pretty) {
    switch (v) {
      case null -> sb.append("null");
      case String s -> string(s, sb);
      case Boolean b -> sb.append(b);
      case Integer n -> sb.append(n.intValue());
      case Long n -> sb.append(n.longValue());
      case Short n -> sb.append(n.shortValue());
      case UUID u -> string(u.toString(), sb);
      case Instant t -> string(MILLIS.format(t.truncatedTo(ChronoUnit.MILLIS)), sb);
      case LocalDate d -> string(d.toString(), sb);
      case Map<?, ?> m -> {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          if (pretty && depth < 2) {
            newline(sb, depth + 1);
          }
          string(String.valueOf(e.getKey()), sb);
          sb.append(pretty && depth < 2 ? ": " : ":");
          value(e.getValue(), sb, depth + 1, pretty);
        }
        if (pretty && depth < 2 && !m.isEmpty()) {
          newline(sb, depth);
        }
        sb.append('}');
      }
      case Collection<?> c -> {
        sb.append('[');
        boolean first = true;
        for (Object o : c) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          if (pretty && depth < 2) {
            newline(sb, depth + 1);
          }
          value(o, sb, depth + 1, pretty);
        }
        if (pretty && depth < 2 && !c.isEmpty()) {
          newline(sb, depth);
        }
        sb.append(']');
      }
      default -> throw new IllegalArgumentException("unsupported JSON value: " + v.getClass().getName());
    }
  }

  private static void newline(StringBuilder sb, int depth) {
    sb.append('\n');
    sb.append("  ".repeat(depth));
  }

  static void string(String s, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }
}
