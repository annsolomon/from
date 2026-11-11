package in.dexter.audit.app;

import in.dexter.audit.app.ActivityStore.Hourly;
import in.dexter.audit.app.AuditViews.DashboardView;
import in.dexter.audit.app.AuditViews.HourRow;
import in.dexter.audit.app.AuditViews.ServiceRow;
import in.dexter.audit.app.AuditViews.Totals;
import in.dexter.core.error.ValidationException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Gateway activity (MASTER_PLAN P18): each sampled request recorded once (raw, 90 days of daily partitions) and
 * added to its hour; the admin dashboard reads only the hourly aggregate.
 */
public final class ActivityService {
  public static final String PATH = "/v1/admin/activity";
  private final TenantTx appTx;
  private final ActivityStore appStore;
  private final TenantTx adminTx;
  private final ActivityStore adminStore;
  private final Clock clock;

  public ActivityService(TenantTx appTx, ActivityStore appStore, TenantTx adminTx, ActivityStore adminStore,
      Clock clock) {
    this.appTx = appTx;
    this.appStore = appStore;
    this.adminTx = adminTx;
    this.adminStore = adminStore;
    this.clock = clock;
  }

  /** The consumer's effect (its own transaction). False for a redelivered event. */
  public boolean record(ActivityStore.Event e) {
    return appTx.write(AuditTenants.SYSTEM, () -> appStore.record(e));
  }

  public DashboardView dashboard(Tenant operator, int hours) {
    if (hours < 1 || hours > 24 * 14) {
      throw ValidationException.of("hours", "OUT_OF_RANGE", "1 to 336");
    }
    Instant to = clock.instant().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1));
    Instant from = to.minus(Duration.ofHours(hours));
    List<Hourly> rows = adminTx.read(operator, () -> adminStore.hourly(from, to));
    long req = 0, ce = 0, se = 0, ms = 0;
    int max = 0;
    Map<String, long[]> bySvc = new TreeMap<>();
    Map<Instant, long[]> byHour = new TreeMap<>();
    for (Hourly h : rows) {
      req += h.requests();
      ce += h.clientErrors();
      se += h.serverErrors();
      ms += h.totalMs();
      max = Math.max(max, h.maxMs());
      long[] s = bySvc.computeIfAbsent(h.service(), k -> new long[5]);
      s[0] += h.requests();
      s[1] += h.clientErrors();
      s[2] += h.serverErrors();
      s[3] += h.totalMs();
      s[4] = Math.max(s[4], h.maxMs());
      long[] t = byHour.computeIfAbsent(h.hour(), k -> new long[4]);
      t[0] += h.requests();
      t[1] += h.clientErrors();
      t[2] += h.serverErrors();
      t[3] += h.totalMs();
    }
    List<ServiceRow> services = new ArrayList<>();
    bySvc.forEach((k, s) -> services.add(new ServiceRow(k, s[0], s[1], s[2], avg(s[3], s[0]), (int) s[4])));
    List<HourRow> hourly = new ArrayList<>();
    byHour.forEach((k, t) -> hourly.add(new HourRow(k.toString(), t[0], t[1], t[2], avg(t[3], t[0]))));
    return new DashboardView(from.toString(), to.toString(), hours, new Totals(req, ce, se, avg(ms, req), max),
        services, hourly);
  }

  static long avg(long total, long n) {
    return n == 0 ? 0 : total / n;
  }
}
