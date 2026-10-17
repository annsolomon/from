package in.dexter.identity.app;

/** POST /v1/auth/register body. {@code clientId} defaults to the app client; {@code tz} to Asia/Kolkata. */
public record RegisterCommand(String email, String password, String displayName, String tz, String clientId) {
  /** Without the password: what the Idempotency-Key fingerprint covers (a password hash must never be stored
   * where a fast hash of it could be guessed offline). */
  public record Fingerprint(String email, String displayName, String tz, String clientId) {}
}
