package in.dexter.seed;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Writes one table as PostgreSQL COPY text format (tab separated, {@code \N} for NULL, backslash escapes) and keeps
 * the SHA-256 and row count as it goes. Values are encoded by Java type, so every table encodes the same way:
 * {@code UUID}, {@code Long}/{@code Integer}/{@code Short}, {@code Boolean} → t/f, {@link Instant} → UTC ISO-8601 at
 * millisecond precision, {@link LocalDate}, {@code List<String>} → array literal, {@link Raw} → verbatim text
 * (jsonb, ranges), any other {@code String} → escaped text.
 */
public final class CopyWriter implements AutoCloseable {
  private static final DateTimeFormatter MILLIS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(java.time.ZoneOffset.UTC);

  /** Text written as-is inside the column (still COPY-escaped): jsonb, tstzrange, bytea hex. */
  public record Raw(String text) {}

  private final Table table;
  private final MessageDigest digest;
  private final OutputStream out;
  private final StringBuilder line = new StringBuilder(256);
  private long rows;

  public CopyWriter(Table table, Path file) {
    this.table = table;
    try {
      Files.createDirectories(file.getParent());
      this.digest = MessageDigest.getInstance("SHA-256");
      this.out = new BufferedOutputStream(new DigestOutputStream(Files.newOutputStream(file), digest), 1 << 16);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public void row(Object... values) {
    if (values.length != table.columns().size()) {
      throw new IllegalArgumentException(table.name() + ": " + values.length + " values for "
          + table.columns().size() + " columns");
    }
    line.setLength(0);
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        line.append('\t');
      }
      encode(values[i], line);
    }
    line.append('\n');
    try {
      out.write(line.toString().getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    rows++;
  }

  static void encode(Object v, StringBuilder sb) {
    switch (v) {
      case null -> sb.append("\\N");
      case String s -> escape(s, sb);
      case Raw r -> escape(r.text(), sb);
      case UUID u -> sb.append(u);
      case Long l -> sb.append(l.longValue());
      case Integer n -> sb.append(n.intValue());
      case Short n -> sb.append(n.shortValue());
      case Boolean b -> sb.append(b ? 't' : 'f');
      case Instant t -> sb.append(MILLIS.format(t.truncatedTo(ChronoUnit.MILLIS)));
      case LocalDate d -> sb.append(d);
      case List<?> list -> {
        StringBuilder arr = new StringBuilder("{");
        for (int i = 0; i < list.size(); i++) {
          if (i > 0) {
            arr.append(',');
          }
          arr.append('"');
          String s = String.valueOf(list.get(i));
          for (int j = 0; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '"' || c == '\\') {
              arr.append('\\');
            }
            arr.append(c);
          }
          arr.append('"');
        }
        escape(arr.append('}').toString(), sb);
      }
      default -> throw new IllegalArgumentException("unsupported COPY value type: " + v.getClass().getName());
    }
  }

  static void escape(String s, StringBuilder sb) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '\t' -> sb.append("\\t");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        default -> sb.append(c);
      }
    }
  }

  public Table table() {
    return table;
  }

  public long rows() {
    return rows;
  }

  /** Closes the file and returns its SHA-256 (hex). */
  public String finish() {
    close();
    return HexFormat.of().formatHex(digest.digest());
  }

  private boolean closed;

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      out.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
