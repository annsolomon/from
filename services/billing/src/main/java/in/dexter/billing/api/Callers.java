package in.dexter.billing.api;

import in.dexter.billing.domain.BillingErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.security.AuthContext;

/** The tenant of a user request: the token's account as {@code USER}; admin-console tokens are refused. */
final class Callers {
  private Callers() {}

  static Tenant user(AuthContext caller) {
    if (caller.scopes().contains("admin")) {
      throw new DexterException(BillingErrors.USER_TOKEN_REQUIRED, "Admin console tokens cannot act as a user");
    }
    return caller.tenant();
  }
}
