package in.dexter.commerce.app;

import in.dexter.commerce.app.CommerceViews.StatsView;
import in.dexter.commerce.domain.CommerceErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /v1/admin/commerce/stats?from=&to=} (operators): per deal clicks, daily uniques (summed per day),
 * conversions and approved commission, and per campaign impressions and clicks, over IST days — read from the
 * maintained counter tables only (MASTER_PLAN 8.3: never a COUNT over the raw rows at request time). At most 92
 * days; the default is the last 7 including today.
 */
public final class StatsService {
  public static final String PATH = "/v1/admin/commerce/stats";
  public static final int MAX_DAYS = 92;

  private final TenantTx tx;
  private final TrackingStore tracking;
  private final AdStore ads;
  private final Clock clock;

  public StatsService(TenantTx tx, TrackingStore tracking, AdStore ads, Clock clock) {
    this.tx = tx;
    this.tracking = tracking;
    this.ads = ads;
    this.clock = clock;
  }

  public StatsView stats(Tenant operator, @Nullable String fromRaw, @Nullable String toRaw) {
    LocalDate today = ClickService.day(clock.instant());
    LocalDate to;
    LocalDate from;
    try {
      to = toRaw == null || toRaw.isBlank() ? today : LocalDate.parse(toRaw);
      from = fromRaw == null || fromRaw.isBlank() ? to.minusDays(6) : LocalDate.parse(fromRaw);
    } catch (DateTimeParseException e) {
      throw new DexterException(CommerceErrors.STATS_RANGE_INVALID, "from/to are YYYY-MM-DD");
    }
    if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
      throw new DexterException(CommerceErrors.STATS_RANGE_INVALID, "from <= to, at most " + MAX_DAYS + " days");
    }
    LocalDate f = from;
    LocalDate t = to;
    return tx.read(operator, () -> new StatsView(f.toString(), t.toString(), tracking.dealStats(f, t),
        ads.campaignStats(f, t)));
  }
}
