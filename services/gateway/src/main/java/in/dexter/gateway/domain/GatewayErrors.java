package in.dexter.gateway.domain;

import in.dexter.core.error.ErrorCode;

/** Errors only the gateway produces (docs/api/errors.md, gateway section). */
public enum GatewayErrors implements ErrorCode {
  /** The service is down, refused the connection, or its circuit breaker is open. */
  UPSTREAM_UNAVAILABLE(503, "Service unavailable"),
  /** The service did not answer within the route's response timeout. */
  UPSTREAM_TIMEOUT(504, "Service timed out");

  private final int status;
  private final String title;

  GatewayErrors(int status, String title) {
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
