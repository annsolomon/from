package in.dexter.identity.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin endpoints acting on others (identity_admin pool, tenant = the calling admin, actor role = the role the
 * use-case acts with). Writes go through the audited definer functions admin_create / admin_update.
 */
public interface AdminDirectory {
  record AdminView(UUID id, String email, String displayName, String status, boolean breakGlassEligible,
      boolean mfaConfirmed, List<String> roles, Instant createdAt, long version) {}

  record AccountSummary(UUID accountId, String email, String status, boolean emailVerified, boolean mfaEnrolled,
      String tier, Instant createdAt) {}

  Page<AdminView> list(PageRequest page);

  Optional<AdminView> find(UUID id);

  record Created(UUID id, long version, Instant createdAt) {}

  Created create(UUID id, String email, String displayName, List<String> roles, boolean breakGlass, UUID inviteId,
      byte[] inviteHash, Instant inviteExpires);

  record Updated(long version, int sessionsRevoked) {}

  Updated update(UUID id, long expectedVersion, String displayName, String status, List<String> roles,
      Boolean breakGlass, Boolean resetMfa);

  Optional<AccountSummary> findAccount(String email);

  void audit(UUID adminId, String action, String targetType, UUID targetId, String detailsJson);
}
