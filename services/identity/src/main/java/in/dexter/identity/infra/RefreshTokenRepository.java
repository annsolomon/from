package in.dexter.identity.infra;

import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.identity.app.RefreshTokenStore;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code identity.refresh_tokens} under RLS. Indexes: pkey (lock/rotate), refresh_tokens_family (reuse →
 * revoke family), refresh_tokens_session (revoke by session; also the FK index).
 */
public final class RefreshTokenRepository implements RefreshTokenStore {
  private final JdbcClient jdbc;

  public RefreshTokenRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(NewRefresh r) {
    TenantScope.require();
    jdbc.sql("INSERT INTO identity.refresh_tokens (id, account_id, session_id, family_id, token_hash, parent_id,"
            + " issued_at, expires_at, absolute_expires_at) VALUES (:id, :a, :s, :f, :h, :p, :i, :e, :ae)")
        .param("id", r.id()).param("a", r.accountId()).param("s", r.sessionId()).param("f", r.familyId())
        .param("h", r.tokenHash()).param("p", r.parentId()).param("i", Sql.ts(r.issuedAt()))
        .param("e", Sql.ts(r.expiresAt())).param("ae", Sql.ts(r.absoluteExpiresAt()))
        .update();
  }

  @Override
  public Optional<LockedRefresh> lock(UUID accountId, UUID tokenId) {
    TenantScope.require();
    return jdbc.sql("SELECT r.id, r.session_id, r.family_id, r.expires_at, r.absolute_expires_at, r.rotated_at,"
            + " r.revoked_at, (s.revoked_at IS NOT NULL) AS session_revoked"
            + " FROM identity.refresh_tokens r JOIN identity.sessions s ON s.account_id = r.account_id AND s.id = r.session_id"
            + " WHERE r.account_id = :a AND r.id = :id FOR UPDATE OF r")
        .param("a", accountId).param("id", tokenId)
        .query((rs, i) -> new LockedRefresh(Rows.uuid(rs, "id"), Rows.uuid(rs, "session_id"), Rows.uuid(rs, "family_id"),
            Rows.instant(rs, "expires_at"), Rows.instant(rs, "absolute_expires_at"), Rows.instant(rs, "rotated_at"),
            Rows.instant(rs, "revoked_at"), rs.getBoolean("session_revoked")))
        .optional();
  }

  @Override
  public void markRotated(UUID accountId, UUID tokenId, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE identity.refresh_tokens SET rotated_at = :now WHERE account_id = :a AND id = :id")
        .param("now", Sql.ts(now)).param("a", accountId).param("id", tokenId).update();
  }

  @Override
  public int revokeFamily(UUID accountId, UUID familyId, Instant now) {
    TenantScope.require();
    return jdbc.sql("UPDATE identity.refresh_tokens SET revoked_at = :now"
            + " WHERE account_id = :a AND family_id = :f AND revoked_at IS NULL")
        .param("now", Sql.ts(now)).param("a", accountId).param("f", familyId).update();
  }

  @Override
  public int revokeSessions(UUID accountId, Collection<UUID> sessionIds, Instant now) {
    TenantScope.require();
    if (sessionIds.isEmpty()) {
      return 0;
    }
    return jdbc.sql("UPDATE identity.refresh_tokens SET revoked_at = :now"
            + " WHERE account_id = :a AND session_id = ANY(:s) AND revoked_at IS NULL")
        .param("now", Sql.ts(now)).param("a", accountId).param("s", Sql.uuids(sessionIds)).update();
  }
}
