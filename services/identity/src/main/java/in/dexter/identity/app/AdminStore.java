package in.dexter.identity.app;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An admin acting on itself (identity_app, tenant = admin id): sign-in lookup, profile, roles, TOTP, sessions,
 * refresh tokens, elevations and its own audit rows.
 */
public interface AdminStore {
  record AdminLogin(UUID adminId, String passwordHash, String status, boolean mfaConfirmed) {}

  record Admin(UUID id, String email, String displayName, String status, boolean breakGlassEligible) {}

  record Totp(byte[] secretEnc, String kekId, Instant confirmedAt, long lastUsedStep) {}

  record Session(UUID id, UUID adminId, String clientId, List<String> amr, Instant authTime, Instant revokedAt) {}

  record LockedRefresh(UUID id, UUID sessionId, UUID familyId, Instant expiresAt, Instant absoluteExpiresAt,
      Instant rotatedAt, Instant revokedAt, boolean sessionRevoked) {}

  record Elevation(UUID id, Instant startsAt, Instant expiresAt) {}

  // pre-auth (definer functions)
  Optional<AdminLogin> findLogin(String email);

  Optional<PreAuthStore.RefreshRef> findRefresh(byte[] tokenHash);

  record Invite(UUID adminId, String email) {}

  Optional<Invite> acceptInvite(byte[] tokenHash, String passwordHash);

  // self
  Optional<Admin> find(UUID adminId);

  void updatePasswordHash(UUID adminId, String hash);

  List<String> roles(UUID adminId);

  Set<String> permissions(Set<String> roles);

  Optional<Totp> totp(UUID adminId);

  void putPendingTotp(UUID adminId, byte[] secretEnc, String kekId);

  void confirmTotp(UUID adminId, long step, Instant now);

  boolean advanceTotpStep(UUID adminId, long step);

  void createSession(UUID id, UUID adminId, String clientId, byte[] uaHash, String ip, List<String> amr,
      Instant authTime, Instant now);

  Optional<Session> session(UUID adminId, UUID sessionId);

  boolean revokeSession(UUID adminId, UUID sessionId, String reason, Instant now);

  void insertRefresh(UUID id, UUID adminId, UUID sessionId, UUID familyId, byte[] hash, UUID parentId, Instant issuedAt,
      Instant expiresAt, Instant absoluteExpiresAt);

  Optional<LockedRefresh> lockRefresh(UUID adminId, UUID tokenId);

  void markRotated(UUID adminId, UUID tokenId, Instant now);

  int revokeFamily(UUID adminId, UUID familyId, Instant now);

  int revokeSessionTokens(UUID adminId, UUID sessionId, Instant now);

  void touchSession(UUID adminId, UUID sessionId, Instant now);

  Optional<Elevation> activeElevation(UUID adminId, Instant now);

  void insertElevation(UUID id, UUID adminId, String reason, Instant startsAt, Instant expiresAt);

  Optional<UUID> revokeActiveElevation(UUID adminId, Instant now);

  void audit(UUID adminId, String action, String targetType, UUID targetId, String detailsJson);
}
