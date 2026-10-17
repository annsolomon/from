package in.dexter.identity.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.identity.domain.RevokeReason;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code identity.sessions} of the current tenant. */
public interface SessionStore {

  record NewSession(UUID id, UUID accountId, String clientId, String deviceLabel, byte[] uaHash, String ip,
      List<String> amr, Instant authTime, Instant now) {}

  record SessionRow(UUID id, UUID accountId, String clientId, List<String> amr, Instant authTime, Instant revokedAt) {}

  record SessionView(UUID id, String clientId, String deviceLabel, String ipFirst, Instant createdAt,
      Instant lastSeenAt) {}

  void create(NewSession s);

  Optional<SessionRow> find(UUID accountId, UUID sessionId);

  /** Open sessions, newest first (keyset, MASTER_PLAN 4). */
  Page<SessionView> listActive(PageRequest page);

  /** Revokes one open session; false when it was already revoked or does not exist. */
  boolean revoke(UUID accountId, UUID sessionId, RevokeReason reason, Instant now);

  /** Revokes every open session except {@code keep} (null = all). Returns the ids revoked. */
  List<UUID> revokeAll(UUID accountId, UUID keep, RevokeReason reason, Instant now);

  void touch(UUID accountId, UUID sessionId, Instant now);
}
