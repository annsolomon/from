package in.dexter.identity.infra;

import in.dexter.db.tenant.TenantScope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code identity.account_keys} under RLS: the wrapped per-account data key (envelope encryption, MASTER_PLAN 7.4). */
final class AccountKeyRepository {
  record WrappedKey(byte[] dekEnc, String kekId, boolean destroyed) {}

  private final JdbcClient jdbc;

  AccountKeyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<WrappedKey> find(UUID accountId) {
    TenantScope.require();
    return jdbc.sql("SELECT dek_enc, kek_id, destroyed_at IS NOT NULL AS destroyed FROM identity.account_keys"
            + " WHERE account_id = :a")
        .param("a", accountId)
        .query((rs, i) -> new WrappedKey(rs.getBytes("dek_enc"), rs.getString("kek_id"), rs.getBoolean("destroyed")))
        .optional();
  }

  /** Inserts unless a concurrent transaction already did; the caller then re-reads. */
  void insertIfAbsent(UUID accountId, byte[] dekEnc, String kekId) {
    TenantScope.require();
    jdbc.sql("INSERT INTO identity.account_keys (account_id, dek_enc, kek_id) VALUES (:a, :d, :k)"
            + " ON CONFLICT (account_id) DO NOTHING")
        .param("a", accountId).param("d", dekEnc).param("k", kekId).update();
  }
}
