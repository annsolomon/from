package in.dexter.audit.infra;

import in.dexter.audit.app.ActivityStore;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Raw activity rows and the hourly aggregate. */
public final class ActivityRepository implements ActivityStore {
  private final JdbcClient jdbc;

  public ActivityRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean record(Event e) {
    TenantScope.require();
    int inserted = jdbc.sql("INSERT INTO audit.activity_events (id, occurred_at, account_id, request_id, service,"
            + " route_pattern, route_class, method, status, duration_ms) VALUES (:id, :at, :a, :rid, :svc, :rp, :rc,"
            + " :m, :st, :ms) ON CONFLICT DO NOTHING")
        .param("id", e.id()).param("at", Sql.ts(e.occurredAt())).param("a", e.accountId())
        .param("rid", e.requestId()).param("svc", e.service()).param("rp", e.routePattern())
        .param("rc", e.routeClass()).param("m", e.method()).param("st", e.status())
        .param("ms", (int) Math.min(Integer.MAX_VALUE, Math.max(0, e.durationMs()))).update();
    if (inserted == 0) {
      return false;
    }
    int ms = (int) Math.min(Integer.MAX_VALUE, Math.max(0, e.durationMs()));
    jdbc.sql("INSERT INTO audit.activity_hourly AS h (hour, service, route_class, requests, client_errors,"
            + " server_errors, total_ms, max_ms) VALUES (:hour, :svc, :rc, 1, :ce, :se, :ms, :ms)"
            + " ON CONFLICT (hour, service, route_class) DO UPDATE SET requests = h.requests + 1,"
            + " client_errors = h.client_errors + EXCLUDED.client_errors,"
            + " server_errors = h.server_errors + EXCLUDED.server_errors, total_ms = h.total_ms + EXCLUDED.total_ms,"
            + " max_ms = greatest(h.max_ms, EXCLUDED.max_ms)")
        .param("hour", Sql.ts(e.occurredAt().truncatedTo(ChronoUnit.HOURS))).param("svc", e.service())
        .param("rc", e.routeClass()).param("ce", e.status() >= 400 && e.status() < 500 ? 1 : 0)
        .param("se", e.status() >= 500 ? 1 : 0).param("ms", ms).update();
    return true;
  }

  @Override
  public List<Hourly> hourly(Instant from, Instant to) {
    TenantScope.require();
    return jdbc.sql("SELECT hour, service, route_class, requests, client_errors, server_errors, total_ms, max_ms"
            + " FROM audit.activity_hourly WHERE hour >= :from AND hour < :to ORDER BY hour, service, route_class")
        .param("from", Sql.ts(from)).param("to", Sql.ts(to))
        .query((rs, i) -> new Hourly(rs.getObject("hour", java.time.OffsetDateTime.class).toInstant(),
            rs.getString("service"), rs.getString("route_class"), rs.getLong("requests"), rs.getLong("client_errors"),
            rs.getLong("server_errors"), rs.getLong("total_ms"), rs.getInt("max_ms")))
        .list();
  }
}
