package in.dexter.audit.api;

import in.dexter.audit.domain.AuditErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.security.AuthContext;

/** The tenant of an audit request: the user themself, or an admin acting as SUPPORT or SUPER_ADMIN. */
final class Callers {
  private Callers() {}

  static Tenant user(AuthContext caller) {
    if (caller.kind() != AuthContext.Kind.USER) {
      throw new DexterException(AuditErrors.USER_TOKEN_REQUIRED, "This endpoint takes a user token");
    }
    return caller.tenant();
  }

  /** The operator role RLS sees (the permission itself was checked by {@code @RequiresPermission}). */
  static Tenant operator(AuthContext caller) {
    if (caller.kind() != AuthContext.Kind.ADMIN) {
      throw new DexterException(AuditErrors.ADMIN_TOKEN_REQUIRED, "Audit administration needs an admin token");
    }
    if (caller.hasRole(Tenant.SUPER_ADMIN)) {
      return caller.tenantAs(Tenant.SUPER_ADMIN);
    }
    if (caller.hasRole(Tenant.SUPPORT)) {
      return caller.tenantAs(Tenant.SUPPORT);
    }
    throw new DexterException(AuditErrors.ADMIN_TOKEN_REQUIRED, "No operator role on this admin token");
  }

  /** Who the audit log names for an operator action. */
  static String operatorId(AuthContext caller) {
    return caller.accountId() != null ? caller.accountId().toString() : caller.subject();
  }
}
