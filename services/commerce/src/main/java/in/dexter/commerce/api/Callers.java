package in.dexter.commerce.api;

import in.dexter.commerce.domain.CommerceErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.security.AuthContext;
import java.util.List;

/** The tenant of a commerce request: the user themself, or an admin acting in one of the operator roles. */
final class Callers {
  private static final List<String> OPERATOR_ROLES = List.of(Tenant.SUPPORT, Tenant.CATALOG_EDITOR, Tenant.SUPER_ADMIN);

  private Callers() {}

  static Tenant user(AuthContext caller) {
    if (caller.kind() != AuthContext.Kind.USER) {
      throw new DexterException(CommerceErrors.USER_TOKEN_REQUIRED, "This endpoint takes a user token");
    }
    return caller.tenant();
  }

  /**
   * The operator role RLS sees: {@code preferred} when the admin holds it (SUPPORT decides applications,
   * CATALOG_EDITOR runs deals and campaigns), else another operator role they hold. The permission itself was
   * checked by {@code @RequiresPermission} before this runs.
   */
  static Tenant operator(AuthContext caller, String preferred) {
    if (caller.kind() != AuthContext.Kind.ADMIN) {
      throw new DexterException(CommerceErrors.ADMIN_TOKEN_REQUIRED, "Commerce administration needs an admin token");
    }
    if (caller.hasRole(preferred)) {
      return caller.tenantAs(preferred);
    }
    for (String r : OPERATOR_ROLES) {
      if (caller.hasRole(r)) {
        return caller.tenantAs(r);
      }
    }
    throw new DexterException(CommerceErrors.ADMIN_TOKEN_REQUIRED, "No operator role on this admin token");
  }
}
