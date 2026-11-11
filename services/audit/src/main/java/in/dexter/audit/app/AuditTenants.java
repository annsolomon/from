package in.dexter.audit.app;

import in.dexter.core.tenant.Tenant;
import java.util.UUID;

/** The actor no token carries (the migration's {@code audit.is_system()}). */
public final class AuditTenants {
  /** The event consumers, the notification worker, the privacy saga and the anchor job: work across accounts. */
  public static final Tenant SYSTEM = new Tenant(new UUID(0, 0xA0D17L), "SYSTEM", null);

  private AuditTenants() {}
}
