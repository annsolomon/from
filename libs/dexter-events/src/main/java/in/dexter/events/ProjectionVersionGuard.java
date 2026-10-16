package in.dexter.events;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Applies a projection write only if it is newer (MASTER_PLAN 6.4): the SQL must guard on {@code
 * projected_version < :version} (UPDATE) or {@code projected_version < EXCLUDED.projected_version}
 * (INSERT ... ON CONFLICT DO UPDATE ... WHERE). Zero rows touched means the event was stale or
 * out of order and is ignored.
 */
public final class ProjectionVersionGuard {
  private static final Pattern GUARD = Pattern.compile(
      "(?is).*\\bprojected_version\\s*<\\s*(:version\\b|EXCLUDED\\.projected_version\\b).*");

  private ProjectionVersionGuard() {}

  /** @return true if the row was written (newer version), false if the event was stale */
  public static boolean apply(JdbcClient jdbc, String sql, Map<String, ?> params, long version) {
    Objects.requireNonNull(jdbc, "jdbc");
    if (sql == null || !GUARD.matcher(sql).matches()) {
      throw new IllegalArgumentException("projection SQL must guard on projected_version < :version");
    }
    if (params != null && params.containsKey("version")) {
      throw new IllegalArgumentException("pass the version as the argument, not in params");
    }
    return jdbc.sql(sql).params(params == null ? Map.of() : params).param("version", version).update() > 0;
  }
}
