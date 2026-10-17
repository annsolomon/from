package in.dexter.gateway.infra;

/**
 * WebFilter order (MASTER_PLAN P06 step 2). Activity wraps everything (it measures the whole request); then
 * request-id, security headers (response), identity-header strip + client IP, CORS (answers preflights before any
 * token check), body size, JWT pre-check, rate limit. The per-route circuit breaker runs inside the route.
 */
public final class FilterOrder {
  public static final int ACTIVITY = -2000;
  public static final int REQUEST_ID = -1900;
  public static final int SECURITY_HEADERS = -1800;
  public static final int STRIP_IDENTITY = -1700;
  public static final int CORS = -1600;
  public static final int BODY_SIZE = -1500;
  public static final int JWT = -1400;
  public static final int RATE_LIMIT = -1300;

  private FilterOrder() {}
}
