package in.dexter.gateway.domain;

/**
 * Rate-limit classes (MASTER_PLAN 7.3): {@code auth} 10/min, {@code write} 120/min, {@code read} 600/min,
 * {@code calc} 60/min, per account when the caller is authenticated, else per client IP.
 */
public enum RouteClass {
  AUTH(10),
  WRITE(120),
  READ(600),
  CALC(60);

  private final int defaultPerMinute;

  RouteClass(int defaultPerMinute) {
    this.defaultPerMinute = defaultPerMinute;
  }

  public int defaultPerMinute() {
    return defaultPerMinute;
  }

  public String id() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }

  /** Sign-in style endpoints are {@code auth}; {@code /v1/calc/**} is {@code calc}; else by method. */
  public static RouteClass of(String method, String path) {
    if (path.startsWith("/v1/auth/") || path.startsWith("/v1/admin/auth/")) {
      return AUTH;
    }
    if (path.startsWith("/v1/calc/")) {
      return CALC;
    }
    return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method) ? READ : WRITE;
  }
}
