package in.dexter.idempotency;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/**
 * Canonical JSON for request hashing (MASTER_PLAN 5.2): object keys sorted, no whitespace, numbers
 * normalised ({@code 1.0} and {@code 1} are the same), minimal string escaping. Two bodies that differ
 * only in key order or spacing hash the same; any value change hashes differently.
 */
public final class CanonicalJson {
  private CanonicalJson() {}

  /** Serialises {@code body} with the service's mapper, then canonicalises the result. */
  public static String of(ObjectMapper mapper, Object body) {
    if (body == null) {
      return "null";
    }
    if (body instanceof String s) {
      // a raw JSON text (for example a request body read as a string)
      return write(mapper.readValue(s, Object.class));
    }
    return write(mapper.convertValue(body, Object.class));
  }

  /** Canonical text of a plain Java tree (Map / List / String / Number / Boolean / null). */
  public static String write(Object tree) {
    StringBuilder sb = new StringBuilder(64);
    write(sb, tree, 0);
    return sb.toString();
  }

  private static void write(StringBuilder sb, Object v, int depth) {
    if (depth > 64) {
      throw new IllegalArgumentException("JSON nested too deeply");
    }
    switch (v) {
      case null -> sb.append("null");
      case String s -> string(sb, s);
      case Boolean b -> sb.append(b);
      case Number n -> sb.append(number(n));
      case Map<?, ?> m -> {
        TreeMap<String, Object> sorted = new TreeMap<>();
        m.forEach((k, val) -> sorted.put(String.valueOf(k), val));
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          string(sb, e.getKey());
          sb.append(':');
          write(sb, e.getValue(), depth + 1);
        }
        sb.append('}');
      }
      case List<?> l -> {
        sb.append('[');
        for (int i = 0; i < l.size(); i++) {
          if (i > 0) {
            sb.append(',');
          }
          write(sb, l.get(i), depth + 1);
        }
        sb.append(']');
      }
      default -> throw new IllegalArgumentException("not a JSON value: " + v.getClass().getName());
    }
  }

  private static String number(Number n) {
    return switch (n) {
      case Integer i -> i.toString();
      case Long l -> l.toString();
      case Short s -> s.toString();
      case Byte b -> b.toString();
      case java.math.BigInteger bi -> bi.toString();
      default -> {
        BigDecimal d = n instanceof BigDecimal bd ? bd : new BigDecimal(n.toString());
        BigDecimal s = d.stripTrailingZeros();
        yield s.signum() == 0 ? "0" : s.toPlainString();
      }
    };
  }

  static void string(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
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
