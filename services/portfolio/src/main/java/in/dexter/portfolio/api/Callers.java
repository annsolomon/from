package in.dexter.portfolio.api;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.portfolio.domain.PortfolioErrors;
import in.dexter.security.AuthContext;
import java.util.UUID;

/** The tenant of a user request: the token's account as {@code USER}; admin-console tokens are refused. */
final class Callers {
  private Callers() {}

  static Tenant user(AuthContext caller) {
    if (caller.scopes().contains("admin")) {
      throw new DexterException(PortfolioErrors.USER_TOKEN_REQUIRED, "Admin console tokens cannot act as a user");
    }
    return caller.tenant();
  }

  /** {@code <uuid>:<action>} path segments (AIP-136 custom methods). */
  static UUID id(String s) {
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw new DexterException(CoreErrors.INVALID_ID, "Not an id");
    }
  }
}
