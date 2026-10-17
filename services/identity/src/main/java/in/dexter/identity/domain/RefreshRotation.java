package in.dexter.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The refresh-token rules of MASTER_PLAN 7.1, as pure functions: 30-day sliding window capped by a 90-day
 * absolute limit, rotation on every use, and family reuse detection — presenting a token that was already
 * rotated means a copy exists somewhere else, so the whole family (session) is revoked.
 */
public final class RefreshRotation {
  /** What to do with a presented refresh token. */
  public enum Outcome {
    /** Valid: rotate it (issue a child, mark this one rotated). */
    ROTATE,
    /** Already rotated: revoke the family, raise a security alert, answer 401. */
    REUSED,
    /** Expired (idle or absolute): 401, nothing to revoke. */
    EXPIRED,
    /** Revoked token or session: 401. */
    REVOKED
  }

  /** The stored state of one refresh token (and whether its session is still open). */
  public record State(Instant expiresAt, Instant absoluteExpiresAt, Instant rotatedAt, Instant revokedAt,
      boolean sessionRevoked) {}

  private RefreshRotation() {}

  public static Outcome decide(State s, Instant now) {
    Objects.requireNonNull(s, "state");
    if (s.revokedAt() != null || s.sessionRevoked()) {
      return Outcome.REVOKED;
    }
    if (s.rotatedAt() != null) {
      return Outcome.REUSED;
    }
    if (!now.isBefore(s.expiresAt()) || !now.isBefore(s.absoluteExpiresAt())) {
      return Outcome.EXPIRED;
    }
    return Outcome.ROTATE;
  }

  /** Expiry of a newly issued token: {@code now + idle}, never past the family's absolute limit. */
  public static Instant nextExpiry(Instant now, Duration idle, Instant absoluteExpiresAt) {
    Instant sliding = now.plus(idle);
    return sliding.isBefore(absoluteExpiresAt) ? sliding : absoluteExpiresAt;
  }
}
