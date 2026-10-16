package in.dexter.db.sql;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Typed, null-safe ResultSet readers used by row mappers. */
public final class Rows {
  private Rows() {}

  public static UUID uuid(ResultSet rs, String col) throws SQLException {
    return rs.getObject(col, UUID.class);
  }

  public static Instant instant(ResultSet rs, String col) throws SQLException {
    OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  public static LocalDate date(ResultSet rs, String col) throws SQLException {
    return rs.getObject(col, LocalDate.class);
  }

  public static Long nullableLong(ResultSet rs, String col) throws SQLException {
    long v = rs.getLong(col);
    return rs.wasNull() ? null : v;
  }

  public static Integer nullableInt(ResultSet rs, String col) throws SQLException {
    int v = rs.getInt(col);
    return rs.wasNull() ? null : v;
  }

  public static String[] textArray(ResultSet rs, String col) throws SQLException {
    Array a = rs.getArray(col);
    return a == null ? null : (String[]) a.getArray();
  }

  /** Reads a column into the requested Java type (used by {@link RecordRowMapper}). */
  public static Object read(ResultSet rs, String col, Class<?> type) throws SQLException {
    if (type == String.class) {
      return rs.getString(col);
    }
    if (type == UUID.class) {
      return uuid(rs, col);
    }
    if (type == Instant.class) {
      return instant(rs, col);
    }
    if (type == OffsetDateTime.class) {
      return rs.getObject(col, OffsetDateTime.class);
    }
    if (type == LocalDate.class) {
      return date(rs, col);
    }
    if (type == long.class) {
      return rs.getLong(col);
    }
    if (type == Long.class) {
      return nullableLong(rs, col);
    }
    if (type == int.class) {
      return rs.getInt(col);
    }
    if (type == Integer.class) {
      return nullableInt(rs, col);
    }
    if (type == short.class || type == Short.class) {
      short v = rs.getShort(col);
      return rs.wasNull() && type == Short.class ? null : v;
    }
    if (type == boolean.class) {
      return rs.getBoolean(col);
    }
    if (type == Boolean.class) {
      boolean v = rs.getBoolean(col);
      return rs.wasNull() ? null : v;
    }
    if (type == BigDecimal.class) {
      return rs.getBigDecimal(col);
    }
    if (type == byte[].class) {
      return rs.getBytes(col);
    }
    if (type == String[].class) {
      return textArray(rs, col);
    }
    if (type.isEnum()) {
      String v = rs.getString(col);
      return v == null ? null : enumValue(type, v);
    }
    throw new IllegalArgumentException("unsupported column type " + type.getName() + " for " + col);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object enumValue(Class<?> type, String v) {
    return Enum.valueOf((Class) type, v.toUpperCase(java.util.Locale.ROOT));
  }
}
