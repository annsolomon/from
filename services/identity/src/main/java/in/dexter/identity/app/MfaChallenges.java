package in.dexter.identity.app;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The second step of a sign-in (MASTER_PLAN P05): after the password, the client holds an opaque {@code mfaToken}
 * (5 min, single use, 5 wrong codes) instead of tokens. Infra: Redis, only sha256(token) as key.
 */
public interface MfaChallenges {
  /** Who is signing in and from where. {@code setup}: an admin without MFA must enrol before tokens are issued. */
  record Challenge(Kind kind, UUID principalId, String clientId, String deviceLabel, String ip, String uaHashB64,
      boolean setup) {}

  enum Kind { USER, ADMIN }

  String issue(Challenge c, Duration ttl);

  Optional<Challenge> peek(String token);

  /** Atomically removes and returns the challenge (exactly one caller wins). */
  Optional<Challenge> consume(String token);

  /** Counts a wrong code; returns the number of failures so far. */
  int fail(String token, Duration ttl);
}
