package in.dexter.identity.app;

import java.time.Instant;
import java.util.UUID;

/** {@code identity.verification_tokens} of the current tenant (password reset; email verification is issued by
 * {@code auth_register}). */
public interface VerificationTokenStore {

  /** Tokens of {@code purpose} issued since {@code since} (used ones count: it is a send throttle). */
  int countSince(UUID accountId, String purpose, Instant since);

  /** Supersedes the open tokens of {@code purpose} and stores a new one. */
  void issue(UUID id, UUID accountId, String purpose, byte[] tokenHash, Instant expiresAt);
}
