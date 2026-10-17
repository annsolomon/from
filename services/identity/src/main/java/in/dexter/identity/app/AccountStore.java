package in.dexter.identity.app;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** {@code identity.accounts} for the signed-in account (tenant transaction; RLS shows only its own row). */
public interface AccountStore {

  record Account(UUID id, String email, String displayName, byte[] phoneEnc, String status, Instant emailVerifiedAt,
      String tz, String tier, String passwordHash, Instant createdAt, long version) {}

  Optional<Account> find(UUID id);

  /** Versioned profile write (MASTER_PLAN 3.4): 412 on a stale version, 404 when invisible. Returns the new version. */
  long updateProfile(UUID id, long expectedVersion, String displayName, String tz, byte[] phoneEnc, byte[] phoneHash);

  /** New password hash. {@code changed=false} is a transparent rehash (parameters upgraded), not a user change. */
  void updatePasswordHash(UUID id, String passwordHash, boolean changed);
}
