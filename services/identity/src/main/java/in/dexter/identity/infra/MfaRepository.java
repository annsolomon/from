package in.dexter.identity.infra;

import in.dexter.core.id.Ids;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.identity.app.MfaStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code identity.mfa_totp} / {@code identity.recovery_codes} under RLS (tenant = the account). Indexes: mfa_totp
 * pkey; recovery_codes_account_id_code_hash_key (use); recovery_codes_unused (count left).
 */
public final class MfaRepository implements MfaStore {
  private final JdbcClient jdbc;

  public MfaRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<TotpRow> find(UUID accountId) {
    TenantScope.require();
    return jdbc.sql("SELECT secret_enc, confirmed_at, last_used_step FROM identity.mfa_totp WHERE account_id = :a")
        .param("a", accountId)
        .query((rs, i) -> new TotpRow(rs.getBytes("secret_enc"), Rows.instant(rs, "confirmed_at"),
            rs.getLong("last_used_step")))
        .optional();
  }

  @Override
  public boolean putPending(UUID accountId, byte[] secretEnc) {
    TenantScope.require();
    return jdbc.sql("INSERT INTO identity.mfa_totp (account_id, secret_enc) VALUES (:a, :s)"
            + " ON CONFLICT (account_id) DO UPDATE SET secret_enc = EXCLUDED.secret_enc, last_used_step = 0,"
            + " updated_at = now() WHERE identity.mfa_totp.confirmed_at IS NULL")
        .param("a", accountId).param("s", secretEnc).update() == 1;
  }

  @Override
  public void confirm(UUID accountId, long step, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE identity.mfa_totp SET confirmed_at = :now, last_used_step = :st, updated_at = :now"
            + " WHERE account_id = :a AND confirmed_at IS NULL")
        .param("now", Sql.ts(now)).param("st", step).param("a", accountId).update();
  }

  @Override
  public boolean advanceStep(UUID accountId, long step) {
    TenantScope.require();
    return jdbc.sql("UPDATE identity.mfa_totp SET last_used_step = :st, updated_at = now()"
            + " WHERE account_id = :a AND confirmed_at IS NOT NULL AND last_used_step < :st")
        .param("st", step).param("a", accountId).update() == 1;
  }

  @Override
  public void delete(UUID accountId) {
    TenantScope.require();
    jdbc.sql("DELETE FROM identity.recovery_codes WHERE account_id = :a").param("a", accountId).update();
    jdbc.sql("DELETE FROM identity.mfa_totp WHERE account_id = :a").param("a", accountId).update();
  }

  @Override
  public void replaceRecoveryCodes(UUID accountId, List<byte[]> hashes) {
    TenantScope.require();
    jdbc.sql("DELETE FROM identity.recovery_codes WHERE account_id = :a").param("a", accountId).update();
    for (byte[] h : hashes) {
      jdbc.sql("INSERT INTO identity.recovery_codes (account_id, id, code_hash) VALUES (:a, :id, :h)")
          .param("a", accountId).param("id", Ids.newId()).param("h", h).update();
    }
  }

  @Override
  public boolean useRecoveryCode(UUID accountId, byte[] hash, Instant now) {
    TenantScope.require();
    return jdbc.sql("UPDATE identity.recovery_codes SET used_at = :now"
            + " WHERE account_id = :a AND code_hash = :h AND used_at IS NULL")
        .param("now", Sql.ts(now)).param("a", accountId).param("h", hash).update() == 1;
  }

  @Override
  public int recoveryCodesLeft(UUID accountId) {
    TenantScope.require();
    return jdbc.sql("SELECT count(*) FROM identity.recovery_codes WHERE account_id = :a AND used_at IS NULL")
        .param("a", accountId).query(Integer.class).single();
  }
}
