package in.dexter.identity.infra;

import in.dexter.core.error.DexterException;
import in.dexter.db.sql.OptimisticUpdate;
import in.dexter.db.sql.Rows;
import in.dexter.db.tenant.TenantScope;
import in.dexter.identity.app.AccountStore;
import in.dexter.identity.domain.IdentityErrors;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code identity.accounts} under RLS (the tenant sees only its own row). Index: accounts_pkey. */
public final class AccountRepository implements AccountStore {
  static final String COLS = "id, email::text AS email, display_name, phone_enc, status, email_verified_at, tz,"
      + " tier_cache, password_hash, created_at, version";
  static final RowMapper<Account> MAPPER = (rs, i) -> new Account(Rows.uuid(rs, "id"), rs.getString("email"),
      rs.getString("display_name"), rs.getBytes("phone_enc"), rs.getString("status"),
      Rows.instant(rs, "email_verified_at"), rs.getString("tz"), rs.getString("tier_cache"),
      rs.getString("password_hash"), Rows.instant(rs, "created_at"), rs.getLong("version"));

  private final JdbcClient jdbc;

  public AccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Account> find(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + COLS + " FROM identity.accounts WHERE id = :id").param("id", id).query(MAPPER).optional();
  }

  @Override
  public long updateProfile(UUID id, long expectedVersion, String displayName, String tz, byte[] phoneEnc,
      byte[] phoneHash) {
    TenantScope.require();
    List<Long> v;
    try {
      v = jdbc.sql("UPDATE identity.accounts SET display_name = :dn, tz = :tz, phone_enc = :pe, phone_hash = :ph,"
              + " updated_at = now(), version = version + 1 WHERE id = :id AND version = :v RETURNING version")
          .param("dn", displayName).param("tz", tz).param("pe", phoneEnc).param("ph", phoneHash)
          .param("id", id).param("v", expectedVersion)
          .query(Long.class).list();
    } catch (DuplicateKeyException e) {
      throw new DexterException(IdentityErrors.PHONE_TAKEN, "This phone number belongs to another account");
    }
    OptimisticUpdate.check(v.size(), () -> find(id).isPresent(), "account", expectedVersion);
    return v.getFirst();
  }

  @Override
  public void updatePasswordHash(UUID id, String passwordHash, boolean changed) {
    TenantScope.require();
    String sql = changed
        ? "UPDATE identity.accounts SET password_hash = :h, password_changed_at = now(), updated_at = now(),"
            + " version = version + 1 WHERE id = :id"
        : "UPDATE identity.accounts SET password_hash = :h WHERE id = :id";
    jdbc.sql(sql).param("h", passwordHash).param("id", id).update();
  }
}
