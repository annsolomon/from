package in.dexter.db.page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny strict JSON reader/writer for cursor payloads only: objects, arrays, strings, integers,
 * booleans, null. Keeps dexter-db free of a JSON library. Bounded input size and nesting depth.
 */
final class MiniJson {
  private static final int MAX_DEPTH = 4;
  private final String s;
  private int i;

  private MiniJson(String s) {
    this.s = s;
  }

  static Object parse(String text) {
    if (text.length() > 4096) {
      throw new IllegalArgumentException("json too large");
    }
    MiniJson p = new MiniJson(text);
    Object v = p.value(0);
    p.ws();
    if (p.i != text.length()) {
      throw new IllegalArgumentException("trailing data");
    }
    return v;
  }

  static String write(Object v) {
    StringBuilder sb = new StringBuilder();
    write(sb, v);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static void write(StringBuilder sb, Object v) {
    if (v == null) {
      sb.append("null");
    } else if (v instanceof String str) {
      sb.append('"');
      for (int k = 0; k < str.length(); k++) {
        char c = str.charAt(k);
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
    } else if (v instanceof Integer || v instanceof Long) {
      sb.append(v);
    } else if (v instanceof Boolean b) {
      sb.append(b);
    } else if (v instanceof List<?> list) {
      sb.append('[');
      for (int k = 0; k < list.size(); k++) {
        if (k > 0) {
          sb.append(',');
        }
        write(sb, list.get(k));
      }
      sb.append(']');
    } else if (v instanceof Map<?, ?> map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        write(sb, e.getKey());
        sb.append(':');
        write(sb, e.getValue());
      }
      sb.append('}');
    } else {
      throw new IllegalArgumentException("unsupported json value " + v.getClass());
    }
  }

  private Object value(int depth) {
    if (depth > MAX_DEPTH) {
      throw new IllegalArgumentException("too deep");
    }
    ws();
    if (i >= s.length()) {
      throw new IllegalArgumentException("unexpected end");
    }
    char c = s.charAt(i);
    return switch (c) {
      case '{' -> object(depth);
      case '[' -> array(depth);
      case '"' -> string();
      case 't' -> literal("true", Boolean.TRUE);
      case 'f' -> literal("false", Boolean.FALSE);
      case 'n' -> literal("null", null);
      default -> number();
    };
  }

  private Map<String, Object> object(int depth) {
    Map<String, Object> m = new LinkedHashMap<>();
    i++;
    ws();
    if (peek() == '}') {
      i++;
      return m;
    }
    while (true) {
      ws();
      String k = string();
      ws();
      expect(':');
      if (m.put(k, value(depth + 1)) != null || m.size() > 16) {
        throw new IllegalArgumentException("duplicate or too many keys");
      }
      ws();
      char c = next();
      if (c == '}') {
        return m;
      }
      if (c != ',') {
        throw new IllegalArgumentException("expected , or }");
      }
    }
  }

  private List<Object> array(int depth) {
    List<Object> l = new ArrayList<>();
    i++;
    ws();
    if (peek() == ']') {
      i++;
      return l;
    }
    while (true) {
      l.add(value(depth + 1));
      if (l.size() > 16) {
        throw new IllegalArgumentException("array too long");
      }
      ws();
      char c = next();
      if (c == ']') {
        return l;
      }
      if (c != ',') {
        throw new IllegalArgumentException("expected , or ]");
      }
    }
  }

  private String string() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (true) {
      char c = next();
      if (c == '"') {
        return sb.toString();
      }
      if (c < 0x20) {
        throw new IllegalArgumentException("control char in string");
      }
      if (c == '\\') {
        char e = next();
        switch (e) {
          case '"', '\\', '/' -> sb.append(e);
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'u' -> {
            if (i + 4 > s.length()) {
              throw new IllegalArgumentException("bad escape");
            }
            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
          }
          default -> throw new IllegalArgumentException("bad escape");
        }
      } else {
        sb.append(c);
      }
    }
  }

  private Object number() {
    int start = i;
    if (peek() == '-') {
      i++;
    }
    while (i < s.length() && Character.isDigit(s.charAt(i))) {
      i++;
    }
    if (i == start || (i == start + 1 && s.charAt(start) == '-')) {
      throw new IllegalArgumentException("bad token");
    }
    return Long.parseLong(s.substring(start, i));
  }

  private Object literal(String word, Object v) {
    if (!s.startsWith(word, i)) {
      throw new IllegalArgumentException("bad literal");
    }
    i += word.length();
    return v;
  }

  private void ws() {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
  }

  private char peek() {
    return i < s.length() ? s.charAt(i) : '\0';
  }

  private char next() {
    if (i >= s.length()) {
      throw new IllegalArgumentException("unexpected end");
    }
    return s.charAt(i++);
  }

  private void expect(char c) {
    if (next() != c) {
      throw new IllegalArgumentException("expected " + c);
    }
  }
}
