package in.dexter.db.sql;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.UUID;
import java.util.regex.Pattern;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.support.SqlArrayValue;

/**
 * Parameter binders for JdbcClient named parameters (MASTER_PLAN 3.4). Use with
 * {@code = ANY(:ids)} for batch loads (no N+1), {@code :path} for ltree, {@code :doc} for jsonb.
 */
public final class Sql {
  private static final Pattern LTREE = Pattern.compile("[A-Za-z0-9_]{1,64}(\\.[A-Za-z0-9_]{1,64}){0,15}");
  private static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

  private Sql() {}

  /** A {@code uuid[]} parameter. */
  public static SqlArrayValue uuids(Collection<UUID> ids) {
    return new SqlArrayValue("uuid", ids.toArray());
  }

  /** A {@code text[]} parameter. */
  public static SqlArrayValue texts(Collection<String> values) {
    return new SqlArrayValue("text", values.toArray());
  }

  /** A {@code bigint[]} parameter. */
  public static SqlArrayValue longs(Collection<Long> values) {
    return new SqlArrayValue("int8", values.toArray());
  }

  /** An {@code ltree} parameter; the label syntax is validated first. */
  public static PGobject ltree(String path) {
    if (path == null || !LTREE.matcher(path).matches()) {
      throw new IllegalArgumentException("invalid ltree path: " + path);
    }
    return pg("ltree", path);
  }

  public static boolean isValidLtree(String path) {
    return path != null && LTREE.matcher(path).matches();
  }

  /** A {@code jsonb} parameter from already-serialized JSON text. */
  public static PGobject jsonb(String json) {
    return pg("jsonb", json);
  }

  /** A {@code timestamptz} parameter (pgjdbc binds OffsetDateTime, not Instant). */
  public static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  /** Validates a lower-case SQL identifier (for the few places that build DDL). */
  public static String ident(String name) {
    if (name == null || !IDENT.matcher(name).matches()) {
      throw new IllegalArgumentException("invalid identifier: " + name);
    }
    return name;
  }

  private static PGobject pg(String type, String value) {
    PGobject o = new PGobject();
    o.setType(type);
    try {
      o.setValue(value);
    } catch (SQLException e) {
      throw new IllegalArgumentException(e);
    }
    return o;
  }
}
