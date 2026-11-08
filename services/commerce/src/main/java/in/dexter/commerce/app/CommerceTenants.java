package in.dexter.commerce.app;

import in.dexter.core.tenant.Tenant;
import java.util.UUID;

/** The actors no token carries (the migration's actor predicates). */
public final class CommerceTenants {
  /** Anonymous reads of public data (deals, placements). */
  public static final Tenant PUBLIC = new Tenant(new UUID(0, 0), "PUBLIC", null);
  /** The click/impression logger, the postback handler and the counter flusher (deal_clicks, counters, ...). */
  public static final Tenant TRACKER = new Tenant(new UUID(0, 0xC0_7AC4L), "TRACKER", null);
  /** The catalog consumer writing the global card projection. */
  public static final Tenant PROJECTOR = new Tenant(new UUID(0, 0xC0_9807L), "PROJECTOR", null);

  private CommerceTenants() {}
}
