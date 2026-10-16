package in.dexter.security;

import in.dexter.core.error.ErrorCode;

/**
 * Security error codes (docs/api/errors.md). Missing or rejected tokens use the core
 * {@code UNAUTHENTICATED}; missing permissions or the wrong caller kind use {@code FORBIDDEN}.
 */
public enum SecurityErrors implements ErrorCode {
  /**
   * The action needs a recent login (and MFA when enrolled). 401 with
   * {@code WWW-Authenticate: Bearer error="insufficient_user_authentication"} (RFC 9470), so the
   * client re-authenticates instead of refreshing.
   */
  STEP_UP_REQUIRED(401, "Recent authentication required");

  private final int status;
  private final String title;

  SecurityErrors(int status, String title) {
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
