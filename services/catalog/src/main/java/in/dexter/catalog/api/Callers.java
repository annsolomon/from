package in.dexter.catalog.api;

import in.dexter.catalog.domain.CatalogErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.security.AuthContext;

/** The tenant of an admin catalog request: the admin acting as CATALOG_EDITOR (or SUPER_ADMIN during break-glass). */
final class Callers {
  private Callers() {}

  static Tenant editor(AuthContext caller) {
    if (caller.kind() != AuthContext.Kind.ADMIN) {
      throw new DexterException(CatalogErrors.ADMIN_TOKEN_REQUIRED, "Catalog administration needs an admin token");
    }
    if (caller.hasRole(Tenant.CATALOG_EDITOR)) {
      return caller.tenantAs(Tenant.CATALOG_EDITOR);
    }
    return caller.tenantAs(Tenant.SUPER_ADMIN);
  }
}
