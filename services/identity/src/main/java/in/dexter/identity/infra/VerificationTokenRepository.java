package in.dexter.identity.infra;

import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import in.dexter.identity.app.VerificationTokenStore;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code identity.verification_tokens} under RLS. Index: verification_tokens_recent (account_id, purpose, created_at DESC). */
public final class VerificationTokenRepository implements VerificationTokenStore {
  private final JdbcClient jdbc;

  public VerificationTokenRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public int countSince(UUID accountId, String purpose, Instant since) {
    TenantScope.require();
    return jdbc.sql("SELECT count(1) FROM identity.verification_tokens"
            + " WHERE account_id = :a AND purpose = :p AND created_at > :since")
        .param("a", accountId).param("p", purpose).param("since", Sql.ts(since))
        .query(Integer.class).single();
  }

  @Override
  public void issue(UUID id, UUID accountId, String purpose, byte[] tokenHash, Instant expiresAt) {
    TenantScope.require();
    jdbc.sql("UPDATE identity.verification_tokens SET expires_at = least(expires_at, now())"
            + " WHERE account_id = :a AND purpose = :p AND used_at IS NULL")
        .param("a", accountId).param("p", purpose).update();
    jdbc.sql("INSERT INTO identity.verification_tokens (id, account_id, purpose, token_hash, expires_at)"
            + " VALUES (:id, :a, :p, :h, :e)")
        .param("id", id).param("a", accountId).param("p", purpose).param("h", tokenHash).param("e", Sql.ts(expiresAt))
        .update();
  }
}
