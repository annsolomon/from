package in.dexter.identity.infra;

import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.identity.app.SigningKeyStore;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code identity.signing_keys} (global, a handful of rows). Rotation runs under one advisory xact lock. */
public final class SigningKeyRepository implements SigningKeyStore {
  private final JdbcClient jdbc;

  public SigningKeyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<KeyRow> all() {
    TenantScope.require();
    return jdbc.sql("SELECT kid, alg, public_jwk::text AS public_jwk, private_enc, kek_id, status, created_at,"
            + " activated_at, retired_at FROM identity.signing_keys ORDER BY created_at, kid")
        .query((rs, i) -> new KeyRow(rs.getString("kid"), rs.getString("public_jwk"), rs.getBytes("private_enc"),
            rs.getString("kek_id"), rs.getString("status"), Rows.instant(rs, "created_at"),
            Rows.instant(rs, "activated_at"), Rows.instant(rs, "retired_at")))
        .list();
  }

  @Override
  public boolean tryLock() {
    TenantScope.require();
    return Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext('identity.signing_keys'))")
        .query(Boolean.class).single());
  }

  @Override
  public void insertNext(String kid, String publicJwk, byte[] privateEnc, String kekId) {
    TenantScope.require();
    jdbc.sql("INSERT INTO identity.signing_keys (kid, alg, public_jwk, private_enc, kek_id, status)"
            + " VALUES (:k, 'ES256', CAST(:j AS jsonb), :p, :kek, 'next')")
        .param("k", kid).param("j", publicJwk).param("p", privateEnc).param("kek", kekId).update();
  }

  @Override
  public void activate(String kid, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE identity.signing_keys SET status = 'active', activated_at = :now WHERE kid = :k AND status = 'next'")
        .param("now", Sql.ts(now)).param("k", kid).update();
  }

  @Override
  public void retire(String kid, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE identity.signing_keys SET status = 'retired', retired_at = :now WHERE kid = :k AND status = 'active'")
        .param("now", Sql.ts(now)).param("k", kid).update();
  }

  @Override
  public int deleteRetiredBefore(Instant cutoff) {
    TenantScope.require();
    return jdbc.sql("DELETE FROM identity.signing_keys WHERE status = 'retired' AND retired_at < :c")
        .param("c", Sql.ts(cutoff)).update();
  }
}
