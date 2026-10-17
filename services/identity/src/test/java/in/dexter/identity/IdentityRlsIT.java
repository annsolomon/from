package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.identity.support.IdentityIT;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.rls.RlsProofKit;
import org.junit.jupiter.api.Test;

/**
 * RLS proof (MASTER_PLAN 10) on every identity tenant table, after Flyway built the schema. Child tables need an
 * account row (FKs), so their single-parameter INSERTs create the account in a CTE — under RLS too, so inserting
 * for another tenant already fails at the account.
 */
class IdentityRlsIT extends IdentityIT {
  static final String ACCOUNT = "WITH a AS (INSERT INTO identity.accounts (id, email, password_hash)"
      + " VALUES (?, 'rls-' || gen_random_uuid() || '@it.test', repeat('h', 40)) RETURNING id)";
  static final String SESSION = ACCOUNT + ", s AS (INSERT INTO identity.sessions (id, account_id, client_id, auth_time)"
      + " SELECT gen_random_uuid(), a.id, 'dexter-app', now() FROM a RETURNING id, account_id)";

  @Test
  void accounts() throws Exception {
    new RlsProofKit(TestPostgres.flywayDb("identity"), "accounts", "id",
        "INSERT INTO identity.accounts (id, email, password_hash) VALUES (?, 'rls-' || gen_random_uuid() || '@it.test',"
            + " repeat('h', 40))",
        "UPDATE identity.accounts SET id = ?").proveAll();
  }

  @Test
  void accountKeys() throws Exception {
    new RlsProofKit(TestPostgres.flywayDb("identity"), "account_keys",
        ACCOUNT + " INSERT INTO identity.account_keys (account_id, dek_enc, kek_id) SELECT a.id, '\\x00', 'k1' FROM a",
        "UPDATE identity.account_keys SET account_id = ?").proveAll();
  }

  @Test
  void verificationTokens() throws Exception {
    new RlsProofKit(TestPostgres.flywayDb("identity"), "verification_tokens",
        ACCOUNT + " INSERT INTO identity.verification_tokens (id, account_id, purpose, token_hash, expires_at)"
            + " SELECT gen_random_uuid(), a.id, 'email_verify', sha256(gen_random_uuid()::text::bytea), now() FROM a",
        "UPDATE identity.verification_tokens SET account_id = ?").proveAll();
  }

  @Test
  void sessions() throws Exception {
    new RlsProofKit(TestPostgres.flywayDb("identity"), "sessions",
        ACCOUNT + " INSERT INTO identity.sessions (id, account_id, client_id, auth_time)"
            + " SELECT gen_random_uuid(), a.id, 'dexter-app', now() FROM a",
        "UPDATE identity.sessions SET account_id = ?").proveAll();
  }

  @Test
  void refreshTokens() throws Exception {
    new RlsProofKit(TestPostgres.flywayDb("identity"), "refresh_tokens",
        SESSION + " INSERT INTO identity.refresh_tokens (id, account_id, session_id, family_id, token_hash, issued_at,"
            + " expires_at, absolute_expires_at) SELECT gen_random_uuid(), s.account_id, s.id, gen_random_uuid(),"
            + " sha256(gen_random_uuid()::text::bytea), now(), now() + interval '1 day', now() + interval '2 days' FROM s",
        "UPDATE identity.refresh_tokens SET account_id = ?").proveAll();
  }

  @Test
  void idempotencyKeys() throws Exception {
    new RlsProofKit(TestPostgres.flywayDb("identity"), "idempotency_keys",
        "INSERT INTO identity.idempotency_keys (account_id, idem_key, request_method, request_path, request_hash, state)"
            + " VALUES (?, gen_random_uuid(), 'POST', '/x', '\\x00', 'in_progress')",
        "UPDATE identity.idempotency_keys SET account_id = ?").proveAll();
  }

  @Test
  void preAuthFunctionsAreForTheAppRoleOnly() {
    var db = TestPostgres.flywayDb("identity");
    for (String role : new String[] {"admin", "readonly", "relay"}) {
      assertThatThrownBy(() -> db.exec(role, "SELECT account_id FROM identity.auth_find_login('a@b.c')"))
          .as(role).hasMessageContaining("permission denied");
      assertThatThrownBy(() -> db.exec(role, "SELECT kid FROM identity.signing_keys"))
          .as(role).hasMessageContaining("permission denied");
    }
    db.exec("app", "SELECT account_id FROM identity.auth_find_login('a@b.c')");
  }
}
