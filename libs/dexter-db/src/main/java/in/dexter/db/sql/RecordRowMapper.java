package in.dexter.db.sql;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.RowMapper;

/**
 * Maps a row to a record by component name: {@code occurredAt} reads column {@code occurred_at}.
 * Handles UUID, Instant (timestamptz), LocalDate, primitives, boxed nulls, bytea, text[] and enums.
 * The SQL must select explicit columns (no star selects).
 */
public final class RecordRowMapper<R extends Record> implements RowMapper<R> {
  private static final ConcurrentHashMap<Class<?>, RecordRowMapper<?>> CACHE = new ConcurrentHashMap<>();
  private final Constructor<R> ctor;
  private final String[] columns;
  private final Class<?>[] types;

  private RecordRowMapper(Class<R> type) {
    RecordComponent[] comps = type.getRecordComponents();
    columns = new String[comps.length];
    types = new Class<?>[comps.length];
    for (int i = 0; i < comps.length; i++) {
      columns[i] = snake(comps[i].getName());
      types[i] = comps[i].getType();
    }
    try {
      ctor = type.getDeclaredConstructor(types);
      ctor.setAccessible(true);
    } catch (NoSuchMethodException e) {
      throw new IllegalArgumentException("no canonical constructor on " + type, e);
    }
  }

  @SuppressWarnings("unchecked")
  public static <R extends Record> RecordRowMapper<R> of(Class<R> type) {
    return (RecordRowMapper<R>) CACHE.computeIfAbsent(type, t -> new RecordRowMapper<>(type));
  }

  @Override
  public R mapRow(ResultSet rs, int rowNum) throws SQLException {
    Object[] args = new Object[columns.length];
    for (int i = 0; i < columns.length; i++) {
      args[i] = Rows.read(rs, columns[i], types[i]);
    }
    try {
      return ctor.newInstance(args);
    } catch (InstantiationException | IllegalAccessException e) {
      throw new IllegalStateException(e);
    } catch (InvocationTargetException e) {
      Throwable c = e.getCause();
      if (c instanceof RuntimeException re) {
        throw re;
      }
      throw new IllegalStateException(c);
    }
  }

  static String snake(String camel) {
    StringBuilder sb = new StringBuilder(camel.length() + 4);
    for (char ch : camel.toCharArray()) {
      if (Character.isUpperCase(ch)) {
        sb.append('_').append(Character.toLowerCase(ch));
      } else {
        sb.append(ch);
      }
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }
}
