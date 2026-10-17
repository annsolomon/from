package in.dexter.identity.domain;

import in.dexter.core.error.ErrorCode;

/**
 * Identity's error codes (docs/api/errors.md). Login-type failures never say whether an email exists
 * (MASTER_PLAN 7.1): a wrong password and an unknown email are the same {@link #INVALID_CREDENTIALS}.
 */
public enum IdentityErrors implements ErrorCode {
  /** Unknown email or wrong password — deliberately indistinguishable. */
  INVALID_CREDENTIALS(401, "Invalid email or password"),
  /** Correct password, but the email is not verified yet. */
  EMAIL_NOT_VERIFIED(403, "Email address not verified"),
  /** Correct password, but the account is locked, disabled or being deleted. */
  ACCOUNT_UNAVAILABLE(403, "Account unavailable"),
  /** Refresh token unknown, expired or revoked: sign in again. */
  INVALID_REFRESH_TOKEN(401, "Invalid refresh token"),
  /** An already-rotated refresh token was presented: the whole session was revoked (MASTER_PLAN 7.1). */
  REFRESH_TOKEN_REUSED(401, "Refresh token reuse detected"),
  /** Email-verification or password-reset token unknown, used or expired. */
  INVALID_TOKEN(400, "Invalid or expired token"),
  /** Wrong current password on a password change. */
  CURRENT_PASSWORD_INVALID(403, "Current password is incorrect"),
  /** Unknown public client id. */
  UNKNOWN_CLIENT(400, "Unknown client"),
  /** Token endpoint: client authentication failed (RFC 6749 invalid_client). */
  INVALID_CLIENT(401, "Client authentication failed"),
  /** Token endpoint: only client_credentials is supported (RFC 6749 unsupported_grant_type). */
  UNSUPPORTED_GRANT_TYPE(400, "Unsupported grant type"),
  /** Token endpoint: the client may not request a token for this audience (RFC 8707 invalid_target). */
  INVALID_AUDIENCE(400, "Audience not allowed for this client"),
  /** Phone number already belongs to another account. */
  PHONE_TAKEN(409, "Phone number already in use");

  private final int status;
  private final String title;

  IdentityErrors(int status, String title) {
    this.status = status;
    this.title = title;
  }

  @Override
  public String code() {
    return name();
  }

  @Override
  public int status() {
    return status;
  }

  @Override
  public String title() {
    return title;
  }
}
