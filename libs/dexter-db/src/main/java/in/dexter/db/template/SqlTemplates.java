package in.dexter.db.template;

import in.dexter.db.sql.Sql;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Loads the SQL templates in {@code sql-templates/} and substitutes {@code {{schema}}}. Services copy
 * the rendered SQL into their first Flyway migrations; tests apply it directly.
 */
public final class SqlTemplates {
  /** Apply order. */
  public static final List<String> ALL = List.of(
      "00-base-functions.sql", "10-partitions.sql", "20-idempotency.sql", "30-outbox.sql", "40-inbox.sql");

  private SqlTemplates() {}

  public static String render(String name, String schema) {
    String s = Sql.ident(schema);
    try (InputStream in = SqlTemplates.class.getClassLoader().getResourceAsStream("sql-templates/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("no SQL template " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("{{schema}}", s);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static String renderAll(String schema) {
    StringBuilder sb = new StringBuilder();
    for (String n : ALL) {
      sb.append("-- ==== ").append(n).append('\n').append(render(n, schema)).append('\n');
    }
    return sb.toString();
  }
}
