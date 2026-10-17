package in.dexter.identity.app;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/** {@code identity.refresh_tokens} of the current tenant (only sha256 hashes are stored). */
public interface RefreshTokenStore {

  record NewRefresh(UUID id, UUID accountId, UUID sessionId, UUID familyId, byte[] tokenHash, UUID parentId,
      Instant issuedAt, Instant expiresAt, Instant absoluteExpiresAt) {}

  record LockedRefresh(UUID id, UUID sessionId, UUID familyId, Instant expiresAt, Instant absoluteExpiresAt,
      Instant rotatedAt, Instant revokedAt, boolean sessionRevoked) {}

  void insert(NewRefresh r);

  /** The token row locked {@code FOR UPDATE}, so concurrent refreshes of one token serialise. */
  Optional<LockedRefresh> lock(UUID accountId, UUID tokenId);

  void markRotated(UUID accountId, UUID tokenId, Instant now);

  int revokeFamily(UUID accountId, UUID familyId, Instant now);

  int revokeSessions(UUID accountId, Collection<UUID> sessionIds, Instant now);
}
