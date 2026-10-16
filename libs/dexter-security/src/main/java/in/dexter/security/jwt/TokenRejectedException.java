package in.dexter.security.jwt;

/**
 * An access token was not accepted. {@link #reason()} is for logs and metrics only: clients always
 * get the same {@code 401 UNAUTHENTICATED} (RFC 6750 {@code invalid_token}), except
 * {@link Reason#JWKS_UNAVAILABLE}, which is our fault and answers 503. The message never contains
 * the token or claim values.
 */
public final class TokenRejectedException extends RuntimeException {

  /** Why the token was rejected. */
  public enum Reason {
    MALFORMED,
    ALGORITHM,
    HEADER,
    TYPE,
    KID_MISSING,
    KID_UNKNOWN,
    SIGNATURE,
    ISSUER,
    AUDIENCE,
    EXPIRED,
    NOT_YET_VALID,
    LIFETIME,
    CLAIMS,
    JWKS_UNAVAILABLE
  }

  private final Reason reason;

  public TokenRejectedException(Reason reason, String message) {
    super(reason + ": " + message, null, false, false);
    this.reason = reason;
  }

  public TokenRejectedException(Reason reason, String message, Throwable cause) {
    super(reason + ": " + message, cause, false, false);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
