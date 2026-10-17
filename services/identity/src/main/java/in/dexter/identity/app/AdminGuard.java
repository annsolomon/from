package in.dexter.identity.app;

import in.dexter.core.error.DexterException;
import in.dexter.core.error.ForbiddenException;
import in.dexter.identity.domain.IdentityErrors;
import in.dexter.security.AuthContext;

/**
 * Admin endpoints accept only admin-console tokens ({@code scope=admin}) issued after a multi-factor sign-in
 * ({@code amr} contains {@code mfa}). User tokens never reach an admin use-case, whatever roles they carry; an admin
 * token never acts as a user account. Permissions are checked separately by {@code @RequiresPermission}.
 */
public final class AdminGuard {
  private AdminGuard() {}

  public static AuthContext require(AuthContext caller) {
    if (caller == null || caller.isService() || !caller.scopes().contains(AccessTokens.ADMIN_SCOPE)) {
      throw new ForbiddenException("Admin console token required");
    }
    if (!caller.amr().contains(AuthContext.AMR_MFA)) {
      throw new DexterException(IdentityErrors.ADMIN_MFA_REQUIRED, "Sign in to the admin console with your second factor");
    }
    return caller;
  }

  /** User endpoints: an admin-console token cannot act as an end-user account. */
  public static AuthContext requireUser(AuthContext caller) {
    if (caller != null && caller.scopes().contains(AccessTokens.ADMIN_SCOPE)) {
      throw new ForbiddenException("Admin console tokens cannot be used on user endpoints");
    }
    return caller;
  }
}
