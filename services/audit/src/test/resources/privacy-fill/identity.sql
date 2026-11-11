-- One account with a row in every identity account table. ${account} is replaced by the test.
INSERT INTO identity.accounts (id, email, password_hash, status, display_name) VALUES ('${account}', 'p18-' || '${account}' || '@it.test', repeat('h', 40), 'active', 'Privacy Person');
INSERT INTO identity.account_keys (account_id, dek_enc, kek_id) VALUES ('${account}', '\x0102', 'k1');
INSERT INTO identity.verification_tokens (id, account_id, purpose, token_hash, expires_at) VALUES (gen_random_uuid(), '${account}', 'email_verify', sha256(gen_random_uuid()::text::bytea), now());
INSERT INTO identity.sessions (id, account_id, client_id, auth_time) VALUES (gen_random_uuid(), '${account}', 'dexter-app', now());
INSERT INTO identity.refresh_tokens (id, account_id, session_id, family_id, token_hash, issued_at, expires_at, absolute_expires_at) SELECT gen_random_uuid(), account_id, id, gen_random_uuid(), sha256(gen_random_uuid()::text::bytea), now(), now() + interval '1 day', now() + interval '2 days' FROM identity.sessions WHERE account_id = '${account}';
INSERT INTO identity.idempotency_keys (account_id, idem_key, request_method, request_path, request_hash, state) VALUES ('${account}', gen_random_uuid(), 'POST', '/x', '\x00', 'in_progress');
INSERT INTO identity.mfa_totp (account_id, secret_enc) VALUES ('${account}', '\x00');
INSERT INTO identity.recovery_codes (account_id, id, code_hash) VALUES ('${account}', gen_random_uuid(), sha256(gen_random_uuid()::text::bytea));
