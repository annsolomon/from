package in.dexter.identity.domain;

import in.dexter.core.tenant.Tenant;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Synthetic RLS tenants for work that has no account yet (MASTER_PLAN 3.3 still applies: every unit of work
 * runs in TenantTx). Neither id can collide with an account: accounts use UUIDv7, these are the nil UUID and
 * name-based v3 UUIDs.
 *
 * <ul>
 *   <li>{@link #SYSTEM}: global work (signing keys) and the pre-authentication SECURITY DEFINER lookups. Its
 *       {@code app.account_id} matches no tenant row, so RLS still shows it nothing of any account.</li>
 *   <li>{@link #client(String)}: the Idempotency-Key scope of pre-auth endpoints ("per client id",
 *       MASTER_PLAN 5.1) — registration keys of one client never collide with another client's.</li>
 * </ul>
 */
public final class IdentityTenants {
  public static final String SYSTEM_ROLE = "SYSTEM";
  public static final String PREAUTH_ROLE = "PREAUTH";
  public static final Tenant SYSTEM = new Tenant(new UUID(0L, 0L), SYSTEM_ROLE, ZoneOffset.UTC);

  private IdentityTenants() {}

  public static Tenant client(String clientId) {
    return new Tenant(clientScope(clientId), PREAUTH_ROLE, ZoneOffset.UTC);
  }

  public static UUID clientScope(String clientId) {
    return UUID.nameUUIDFromBytes(("dexter-client:" + clientId).getBytes(StandardCharsets.UTF_8));
  }
}
