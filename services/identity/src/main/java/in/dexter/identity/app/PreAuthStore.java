package in.dexter.identity.app;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pre-authentication operations (MASTER_PLAN 3.2): the SECURITY DEFINER functions of {@code identity}. Called
 * inside {@code TenantTx} with {@link in.dexter.identity.domain.IdentityTenants#SYSTEM} or a client tenant,
 * because there is no account context yet.
 */
public interface PreAuthStore {

  record LoginRow(UUID accountId, String passwordHash, String status, boolean mfaEnabled, String tz, String tier) {}

  record Registration(UUID accountId, boolean created, String status, boolean tokenIssued) {}

  record RefreshRef(UUID tokenId, UUID accountId, UUID sessionId) {}

  record VerifiedEmail(UUID accountId, boolean activated, long version) {}

  record PasswordReset(UUID accountId, List<UUID> revokedSessions) {}

  record AccountRef(UUID accountId, String status) {}

  Optional<LoginRow> findLogin(String email);

  Registration register(UUID newId, String email, String passwordHash, String tz, String displayName, UUID tokenId,
      byte[] tokenHash, Instant tokenExpires, int maxTokensPerHour);

  Optional<RefreshRef> findRefresh(byte[] tokenHash);

  Optional<VerifiedEmail> verifyEmail(byte[] tokenHash);

  Optional<PasswordReset> resetPassword(byte[] tokenHash, String newPasswordHash);

  Optional<AccountRef> findAccount(String email);
}
