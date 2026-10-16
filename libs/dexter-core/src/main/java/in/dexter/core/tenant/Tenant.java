package in.dexter.core.tenant;

import in.dexter.core.time.Clocks;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who a unit of work runs for (MASTER_PLAN 3.3). {@code accountId} feeds {@code app.account_id} and
 * {@code role} feeds {@code app.actor_role} for RLS. It always comes from the authenticated context,
 * never from a path or body.
 */
public record Tenant(UUID accountId, String role, ZoneId tz) {
  public static final String USER = "USER";
  public static final String SUPPORT = "SUPPORT";
  public static final String CATALOG_EDITOR = "CATALOG_EDITOR";
  public static final String FINANCE = "FINANCE";
  public static final String SUPER_ADMIN = "SUPER_ADMIN";
  private static final Pattern ROLE = Pattern.compile("[A-Z][A-Z_]{0,31}");

  public Tenant {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(role, "role");
    if (!ROLE.matcher(role).matches()) {
      throw new IllegalArgumentException("role must be UPPER_SNAKE: " + role);
    }
    tz = tz == null ? Clocks.DEFAULT_ZONE : tz;
  }

  public static Tenant user(UUID accountId) {
    return new Tenant(accountId, USER, Clocks.DEFAULT_ZONE);
  }

  public static Tenant user(UUID accountId, ZoneId tz) {
    return new Tenant(accountId, USER, tz);
  }

  @Override
  public String toString() {
    return "Tenant[" + accountId + "," + role + "," + tz + "]";
  }
}
