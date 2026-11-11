package in.dexter.audit.domain;

import in.dexter.core.error.ErrorCode;

/** Audit, notification and privacy error codes (docs/api/errors.md, the audit section). */
public enum AuditErrors implements ErrorCode {
  /** Admin-console tokens cannot act as a user on the user endpoints. */
  USER_TOKEN_REQUIRED(403, "User token required"),
  /** /v1/admin/audit* and /v1/admin/activity/** take an admin token holding an operator role. */
  ADMIN_TOKEN_REQUIRED(403, "Admin token required"),
  NOTIFICATION_NOT_FOUND(404, "Notification not found"),
  PRIVACY_REQUEST_NOT_FOUND(404, "Privacy request not found"),
  /** One running export and one running deletion per account at a time. */
  PRIVACY_REQUEST_RUNNING(409, "A request of this kind is already running"),
  /** :resume applies to a failed request only. */
  PRIVACY_REQUEST_NOT_FAILED(409, "Only a failed request can be resumed"),
  AUDIT_FILTER_INVALID(400, "Invalid audit filter");

  private final int status;
  private final String title;

  AuditErrors(int status, String title) {
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
