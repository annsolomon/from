package in.dexter.identity.infra;

import in.dexter.core.id.Ids;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.identity.app.AdminStore;
import in.dexter.identity.app.PreAuthStore.RefreshRef;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * An admin acting on itself, as identity_app with tenant = the admin id (policies {@code *_self}). Pre-auth lookups
 * use the definer functions auth_find_admin_login / auth_find_admin_refresh / admin_accept_invite. Indexes:
 * admins pkey, admin_roles pkey, role_permissions pkey, admin_mfa_totp pkey, admin_sessions_admin_id_id_key,
 * admin_refresh_tokens pkey/_family/_session, elevations_active.
 */
public final class AdminRepository implements AdminStore {
  private final JdbcClient jdbc;

  public AdminRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // ------------------------------------------------------------------------------------------- pre-auth

  @Override
  public Optional<AdminLogin> findLogin(String email) {
    TenantScope.require();
    return jdbc.sql("SELECT admin_id, password_hash, status, mfa_confirmed"
            + " FROM identity.auth_find_admin_login(CAST(:e AS citext))")
        .param("e", email)
        .query((rs, i) -> new AdminLogin(Rows.uuid(rs, "admin_id"), rs.getString("password_hash"),
            rs.getString("status"), rs.getBoolean("mfa_confirmed")))
        .optional();
  }

  @Override
  public Optional<RefreshRef> findRefresh(byte[] tokenHash) {
    TenantScope.require();
    return jdbc.sql("SELECT token_id, admin_id, session_id FROM identity.auth_find_admin_refresh(:h)")
        .param("h", tokenHash)
        .query((rs, i) -> new RefreshRef(Rows.uuid(rs, "token_id"), Rows.uuid(rs, "admin_id"),
            Rows.uuid(rs, "session_id")))
        .optional();
  }

  @Override
  public Optional<Invite> acceptInvite(byte[] tokenHash, String passwordHash) {
    TenantScope.require();
    return jdbc.sql("SELECT admin_id, email FROM identity.admin_accept_invite(:h, :p)")
        .param("h", tokenHash).param("p", passwordHash)
        .query((rs, i) -> new Invite(Rows.uuid(rs, "admin_id"), rs.getString("email")))
        .optional();
  }

  // ------------------------------------------------------------------------------------------- self

  @Override
  public Optional<Admin> find(UUID adminId) {
    TenantScope.require();
    return jdbc.sql("SELECT id, email, display_name, status, break_glass_eligible FROM identity.admins WHERE id = :id")
        .param("id", adminId)
        .query((rs, i) -> new Admin(Rows.uuid(rs, "id"), rs.getString("email"), rs.getString("display_name"),
            rs.getString("status"), rs.getBoolean("break_glass_eligible")))
        .optional();
  }

  @Override
  public void updatePasswordHash(UUID adminId, String hash) {
    TenantScope.require();
    jdbc.sql("UPDATE identity.admins SET password_hash = :h, updated_at = now() WHERE id = :id")
        .param("h", hash).param("id", adminId).update();
  }

  @Override
  public List<String> roles(UUID adminId) {
    TenantScope.require();
    return jdbc.sql("SELECT role FROM identity.admin_roles WHERE admin_id = :id ORDER BY role")
        .param("id", adminId).query(String.class).list();
  }

  @Override
  public Set<String> permissions(Set<String> roles) {
    TenantScope.require();
    if (roles.isEmpty()) {
      return Set.of();
    }
    return new TreeSet<>(jdbc.sql("SELECT DISTINCT permission FROM identity.role_permissions WHERE role = ANY(:r)")
        .param("r", Sql.texts(roles)).query(String.class).list());
  }

  @Override
  public Optional<Totp> totp(UUID adminId) {
    TenantScope.require();
    return jdbc.sql("SELECT secret_enc, secret_kek_id, confirmed_at, last_used_step FROM identity.admin_mfa_totp"
            + " WHERE admin_id = :id")
        .param("id", adminId)
        .query((rs, i) -> new Totp(rs.getBytes("secret_enc"), rs.getString("secret_kek_id"),
            Rows.instant(rs, "confirmed_at"), rs.getLong("last_used_step")))
        .optional();
  }

  @Override
  public void putPendingTotp(UUID adminId, byte[] secretEnc, String kekId) {
    TenantScope.require();
    jdbc.sql("INSERT INTO identity.admin_mfa_totp (admin_id, secret_enc, secret_kek_id) VALUES (:id, :s, :k)"
            + " ON CONFLICT (admin_id) DO UPDATE SET secret_enc = EXCLUDED.secret_enc,"
            + " secret_kek_id = EXCLUDED.secret_kek_id, last_used_step = 0"
            + " WHERE identity.admin_mfa_totp.confirmed_at IS NULL")
        .param("id", adminId).param("s", secretEnc).param("k", kekId).update();
  }

  @Override
  public void confirmTotp(UUID adminId, long step, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE identity.admin_mfa_totp SET confirmed_at = :now, last_used_step = :st"
            + " WHERE admin_id = :id AND confirmed_at IS NULL")
        .param("now", Sql.ts(now)).param("st", step).param("id", adminId).update();
  }

  @Override
  public boolean advanceTotpStep(UUID adminId, long step) {
    TenantScope.require();
    return jdbc.sql("UPDATE identity.admin_mfa_totp SET last_used_step = :st"
            + " WHERE admin_id = :id AND confirmed_at IS NOT NULL AND last_used_step < :st")
        .param("st", step).param("id", adminId).update() == 1;
  }

  // ------------------------------------------------------------------------------------------- sessions

  @Override
  public void createSession(UUID id, UUID adminId, String clientId, byte[] uaHash, String ip, List<String> amr,
      Instant authTime, Instant now) {
    TenantScope.require();
    jdbc.sql("INSERT INTO identity.admin_sessions (id, admin_id, client_id, ua_hash, ip_first, amr, auth_time,"
            + " created_at, last_seen_at) VALUES (:id, :a, :c, :ua, CAST(:ip AS inet), :amr, :at, :now, :now)")
        .param("id", id).param("a", adminId).param("c", clientId).param("ua", uaHash).param("ip", ip)
        .param("amr", Sql.texts(amr)).param("at", Sql.ts(authTime)).param("now", Sql.ts(now))
        .update();
  }

  @Override
  public Optional<Session> session(UUID adminId, UUID sessionId) {
    TenantScope.require();
    return jdbc.sql("SELECT id, admin_id, client_id, amr, auth_time, revoked_at FROM identity.admin_sessions"
            + " WHERE admin_id = :a AND id = :id")
        .param("a", adminId).param("id", sessionId)
        .query((rs, i) -> new Session(Rows.uuid(rs, "id"), Rows.uuid(rs, "admin_id"), rs.getString("client_id"),
            texts(rs.getArray("amr")), Rows.instant(rs, "auth_time"), Rows.instant(rs, "revoked_at")))
        .optional();
  }

  @Override
  public boolean revokeSession(UUID adminId, UUID sessionId, String reason, Instant now) {
    TenantScope.require();
    return jdbc.sql("UPDATE identity.admin_sessions SET revoked_at = :now, revoke_reason = :r"
            + " WHERE admin_id = :a AND id = :id AND revoked_at IS NULL")
        .param("now", Sql.ts(now)).param("r", reason).param("a", adminId).param("id", sessionId).update() == 1;
  }

  @Override
  public void insertRefresh(UUID id, UUID adminId, UUID sessionId, UUID familyId, byte[] hash, UUID parentId,
      Instant issuedAt, Instant expiresAt, Instant absoluteExpiresAt) {
    TenantScope.require();
    jdbc.sql("INSERT INTO identity.admin_refresh_tokens (id, admin_id, session_id, family_id, token_hash, parent_id,"
            + " issued_at, expires_at, absolute_expires_at) VALUES (:id, :a, :s, :f, :h, :p, :i, :e, :ae)")
        .param("id", id).param("a", adminId).param("s", sessionId).param("f", familyId).param("h", hash)
        .param("p", parentId).param("i", Sql.ts(issuedAt)).param("e", Sql.ts(expiresAt))
        .param("ae", Sql.ts(absoluteExpiresAt))
        .update();
  }

  @Override
  public Optional<LockedRefresh> lockRefresh(UUID adminId, UUID tokenId) {
    TenantScope.require();
    return jdbc.sql("SELECT r.id, r.session_id, r.family_id, r.expires_at, r.absolute_expires_at, r.rotated_at,"
            + " r.revoked_at, (s.revoked_at IS NOT NULL) AS session_revoked FROM identity.admin_refresh_tokens r"
            + " JOIN identity.admin_sessions s ON s.admin_id = r.admin_id AND s.id = r.session_id"
            + " WHERE r.admin_id = :a AND r.id = :id FOR UPDATE OF r")
        .param("a", adminId).param("id", tokenId)
        .query((rs, i) -> new LockedRefresh(Rows.uuid(rs, "id"), Rows.uuid(rs, "session_id"), Rows.uuid(rs, "family_id"),
            Rows.instant(rs, "expires_at"), Rows.instant(rs, "absolute_expires_at"), Rows.instant(rs, "rotated_at"),
            Rows.instant(rs, "revoked_at"), rs.getBoolean("session_revoked")))
        .optional();
  }

  @Override
  public void markRotated(UUID adminId, UUID tokenId, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE identity.admin_refresh_tokens SET rotated_at = :now WHERE admin_id = :a AND id = :id")
        .param("now", Sql.ts(now)).param("a", adminId).param("id", tokenId).update();
  }

  @Override
  public int revokeFamily(UUID adminId, UUID familyId, Instant now) {
    TenantScope.require();
    return jdbc.sql("UPDATE identity.admin_refresh_tokens SET revoked_at = :now"
            + " WHERE admin_id = :a AND family_id = :f AND revoked_at IS NULL")
        .param("now", Sql.ts(now)).param("a", adminId).param("f", familyId).update();
  }

  @Override
  public int revokeSessionTokens(UUID adminId, UUID sessionId, Instant now) {
    TenantScope.require();
    return jdbc.sql("UPDATE identity.admin_refresh_tokens SET revoked_at = :now"
            + " WHERE admin_id = :a AND session_id = :s AND revoked_at IS NULL")
        .param("now", Sql.ts(now)).param("a", adminId).param("s", sessionId).update();
  }

  @Override
  public void touchSession(UUID adminId, UUID sessionId, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE identity.admin_sessions SET last_seen_at = :now WHERE admin_id = :a AND id = :id")
        .param("now", Sql.ts(now)).param("a", adminId).param("id", sessionId).update();
  }

  // ------------------------------------------------------------------------------------------- elevations + audit

  @Override
  public Optional<Elevation> activeElevation(UUID adminId, Instant now) {
    TenantScope.require();
    return jdbc.sql("SELECT id, starts_at, expires_at FROM identity.elevations"
            + " WHERE admin_id = :a AND revoked_at IS NULL AND expires_at > :now AND starts_at <= :now"
            + " ORDER BY expires_at DESC LIMIT 1")
        .param("a", adminId).param("now", Sql.ts(now))
        .query((rs, i) -> new Elevation(Rows.uuid(rs, "id"), Rows.instant(rs, "starts_at"),
            Rows.instant(rs, "expires_at")))
        .optional();
  }

  @Override
  public void insertElevation(UUID id, UUID adminId, String reason, Instant startsAt, Instant expiresAt) {
    TenantScope.require();
    jdbc.sql("INSERT INTO identity.elevations (id, admin_id, reason, starts_at, expires_at)"
            + " VALUES (:id, :a, :r, :s, :e)")
        .param("id", id).param("a", adminId).param("r", reason).param("s", Sql.ts(startsAt))
        .param("e", Sql.ts(expiresAt)).update();
  }

  @Override
  public Optional<UUID> revokeActiveElevation(UUID adminId, Instant now) {
    TenantScope.require();
    List<UUID> ids = jdbc.sql("UPDATE identity.elevations SET revoked_at = :now"
            + " WHERE admin_id = :a AND revoked_at IS NULL AND expires_at > :now RETURNING id")
        .param("now", Sql.ts(now)).param("a", adminId).query(UUID.class).list();
    return ids.stream().findFirst();
  }

  @Override
  public void audit(UUID adminId, String action, String targetType, UUID targetId, String detailsJson) {
    TenantScope.require();
    insertAudit(jdbc, adminId, action, targetType, targetId, detailsJson);
  }

  static void insertAudit(JdbcClient jdbc, UUID adminId, String action, String targetType, UUID targetId,
      String detailsJson) {
    jdbc.sql("INSERT INTO identity.admin_audit (id, admin_id, action, target_type, target_id, details)"
            + " VALUES (:id, :a, :act, :tt, :tid, :d)")
        .param("id", Ids.newId()).param("a", adminId).param("act", action).param("tt", targetType)
        .param("tid", targetId).param("d", Sql.jsonb(detailsJson == null ? "{}" : detailsJson)).update();
  }

  static List<String> texts(Array a) throws SQLException {
    return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
  }
}
