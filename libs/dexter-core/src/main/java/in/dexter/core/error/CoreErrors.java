package in.dexter.core.error;

/** Error codes shared by every service. Service-specific codes live in each service (docs/api/errors.md). */
public enum CoreErrors implements ErrorCode {
  VALIDATION_FAILED(400, "Request validation failed"),
  MALFORMED_JSON(400, "Malformed JSON body"),
  UNKNOWN_FIELD(400, "Unknown JSON field"),
  DUPLICATE_FIELD(400, "Duplicate JSON field"),
  BAD_REQUEST(400, "Bad request"),
  INVALID_ID(400, "Invalid identifier"),
  LIMIT_INVALID(400, "Invalid page limit"),
  CURSOR_INVALID(400, "Invalid cursor"),
  CURSOR_FILTER_MISMATCH(400, "Cursor does not match the filters"),
  IF_MATCH_INVALID(400, "Invalid If-Match header"),
  IDEMPOTENCY_KEY_REQUIRED(400, "Idempotency-Key header required"),
  UNAUTHENTICATED(401, "Authentication required"),
  FORBIDDEN(403, "Forbidden"),
  NOT_FOUND(404, "Not found"),
  METHOD_NOT_ALLOWED(405, "Method not allowed"),
  NOT_ACCEPTABLE(406, "Not acceptable"),
  CONFLICT(409, "Conflict"),
  IDEMPOTENCY_IN_PROGRESS(409, "Request with this Idempotency-Key is in progress"),
  VERSION_CONFLICT(412, "Resource version conflict"),
  PAYLOAD_TOO_LARGE(413, "Payload too large"),
  UNSUPPORTED_MEDIA_TYPE(415, "Unsupported media type"),
  IDEMPOTENCY_KEY_REUSED(422, "Idempotency-Key reused with a different request"),
  MONEY_OVERFLOW(422, "Amount out of range"),
  IF_MATCH_REQUIRED(428, "If-Match header required"),
  RATE_LIMITED(429, "Too many requests"),
  INTERNAL_ERROR(500, "Internal error"),
  SERVICE_UNAVAILABLE(503, "Service unavailable");

  private final int status;
  private final String title;

  CoreErrors(int status, String title) {
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

  /** Looks up a core code by name; null if unknown. */
  public static CoreErrors byCode(String code) {
    for (CoreErrors c : values()) {
      if (c.name().equals(code)) {
        return c;
      }
    }
    return null;
  }
}
