package in.dexter.identity.infra;

import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.identity.app.PreAuthStore;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The SECURITY DEFINER functions of V202610170901 (the only SQL that sees rows without an account context). */
public final class PreAuthRepository implements PreAuthStore {
  private final JdbcClient jdbc;

  public PreAuthRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<LoginRow> findLogin(String email) {
    TenantScope.require();
    return jdbc.sql("SELECT account_id, password_hash, status, mfa_enabled, tz, tier FROM identity.auth_find_login(CAST(:e AS citext))")
        .param("e", email)
        .query((rs, i) -> new LoginRow(Rows.uuid(rs, "account_id"), rs.getString("password_hash"),
            rs.getString("status"), rs.getBoolean("mfa_enabled"), rs.getString("tz"), rs.getString("tier")))
        .optional();
  }

  @Override
  public Registration register(UUID newId, String email, String passwordHash, String tz, String displayName,
      UUID tokenId, byte[] tokenHash, Instant tokenExpires, int maxTokensPerHour) {
    TenantScope.require();
    return jdbc.sql("SELECT account_id, created, status, token_issued FROM identity.auth_register(:id, CAST(:e AS citext), :h, :tz,"
            + " :dn, :tid, :th, :tx, :max)")
        .param("id", newId).param("e", email).param("h", passwordHash).param("tz", tz).param("dn", displayName)
        .param("tid", tokenId).param("th", tokenHash).param("tx", Sql.ts(tokenExpires)).param("max", maxTokensPerHour)
        .query((rs, i) -> new Registration(Rows.uuid(rs, "account_id"), rs.getBoolean("created"),
            rs.getString("status"), rs.getBoolean("token_issued")))
        .single();
  }

  @Override
  public Optional<RefreshRef> findRefresh(byte[] tokenHash) {
    TenantScope.require();
    return jdbc.sql("SELECT token_id, account_id, session_id FROM identity.auth_find_refresh(:h)")
        .param("h", tokenHash)
        .query((rs, i) -> new RefreshRef(Rows.uuid(rs, "token_id"), Rows.uuid(rs, "account_id"),
            Rows.uuid(rs, "session_id")))
        .optional();
  }

  @Override
  public Optional<VerifiedEmail> verifyEmail(byte[] tokenHash) {
    TenantScope.require();
    return jdbc.sql("SELECT account_id, activated, version FROM identity.auth_verify_email(:h)")
        .param("h", tokenHash)
        .query((rs, i) -> new VerifiedEmail(Rows.uuid(rs, "account_id"), rs.getBoolean("activated"),
            rs.getLong("version")))
        .optional();
  }

  @Override
  public Optional<PasswordReset> resetPassword(byte[] tokenHash, String newPasswordHash) {
    TenantScope.require();
    return jdbc.sql("SELECT account_id, revoked_sessions FROM identity.auth_reset_password(:h, :p)")
        .param("h", tokenHash).param("p", newPasswordHash)
        .query((rs, i) -> new PasswordReset(Rows.uuid(rs, "account_id"), uuids(rs.getArray("revoked_sessions"))))
        .optional();
  }

  @Override
  public Optional<AccountRef> findAccount(String email) {
    TenantScope.require();
    return jdbc.sql("SELECT account_id, status FROM identity.auth_find_account(CAST(:e AS citext))")
        .param("e", email)
        .query((rs, i) -> new AccountRef(Rows.uuid(rs, "account_id"), rs.getString("status")))
        .optional();
  }

  static List<UUID> uuids(Array a) throws SQLException {
    List<UUID> out = new ArrayList<>();
    if (a == null) {
      return out;
    }
    for (Object o : (Object[]) a.getArray()) {
      out.add(o instanceof UUID u ? u : UUID.fromString(o.toString()));
    }
    return out;
  }
}
