package in.dexter.seed;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal RFC 4180 reader for the reference data (header row, quoted fields, no multi-line fields). */
public final class Csv {
  private Csv() {}

  public static List<Map<String, String>> read(String resource) {
    try (InputStream in = Csv.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("seed reference data missing on the classpath: " + resource);
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), resource);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<Map<String, String>> parse(String text, String name) {
    List<Map<String, String>> rows = new ArrayList<>();
    try (BufferedReader r = new BufferedReader(new java.io.StringReader(text))) {
      String headerLine = r.readLine();
      if (headerLine == null) {
        throw new IllegalStateException(name + ": empty");
      }
      List<String> header = fields(headerLine);
      String line;
      int n = 1;
      while ((line = r.readLine()) != null) {
        n++;
        if (line.isBlank()) {
          continue;
        }
        List<String> f = fields(line);
        if (f.size() != header.size()) {
          throw new IllegalStateException(name + ":" + n + ": " + f.size() + " fields, header has " + header.size());
        }
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
          row.put(header.get(i), f.get(i));
        }
        rows.add(row);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return rows;
  }

  static List<String> fields(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            cur.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          cur.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        out.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    if (quoted) {
      throw new IllegalStateException("unterminated quote: " + line);
    }
    out.add(cur.toString());
    return out;
  }

  public static int intOf(Map<String, String> row, String col) {
    return Integer.parseInt(req(row, col));
  }

  public static long longOf(Map<String, String> row, String col) {
    return Long.parseLong(req(row, col));
  }

  public static String req(Map<String, String> row, String col) {
    String v = row.get(col);
    if (v == null) {
      throw new IllegalStateException("column missing: " + col + " in " + row);
    }
    return v;
  }
}
