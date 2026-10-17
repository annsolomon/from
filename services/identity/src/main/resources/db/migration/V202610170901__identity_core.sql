-- P04 Identity A (MASTER_PLAN P04, 3.2, 3.4, 7.1). Runs as identity_owner after V202610170900__platform.
--
-- RLS model
--   * Tenant tables: identity_app sees and writes only rows of app.account_id (accounts: id, others: account_id).
--   * identity_owner policies (USING true): the owner owns these tables and could disable RLS at will, so an
--     owner policy grants nothing new. It is what lets the narrow SECURITY DEFINER functions below (owned by
--     identity_owner, pinned search_path, minimum columns, EXECUTE granted to identity_app only) serve the
--     pre-authentication lookups (login by email, refresh-token lookup, email/reset token use) while FORCE RLS
--     stays on. Flyway and the seed loader also run as the owner.
--   * signing_keys is global (no tenant): identity_app reads and rotates it; nobody else has any grant.
--
-- Query -> index (BUILD_README §4, P04)
--   login by email                    auth_find_login            accounts_email_key (unique citext)
--   register (email taken?)           auth_register              accounts_email_key
--   register / forgot: tokens issued in the last hour, superseding open ones
--                                     (account_id, purpose, created_at DESC)  verification_tokens_recent
--   /me, PATCH /me, password change   accounts by id             accounts_pkey
--   refresh lookup                    auth_find_refresh          refresh_tokens_token_hash_key
--   refresh rotate / lock             refresh_tokens by id       refresh_tokens_pkey
--   revoke family                     (account_id, family_id)    refresh_tokens_family
--   revoke session tokens             (account_id, session_id)   refresh_tokens_session (also the FK index)
--   GET /v1/me/sessions (keyset)      (account_id, created_at DESC, id DESC) WHERE revoked_at IS NULL
--                                                                sessions_active_list
--   logout-all / reset: open sessions (account_id) WHERE revoked_at IS NULL   sessions_active_list (prefix)
--   verification token use            token_hash                 verification_tokens_token_hash_key
--   JWKS / signing key                status IN (next, active, retired)       signing_keys_pkey (tiny table)

-- ---------------------------------------------------------------- accounts
CREATE TABLE identity.accounts (
  id                  uuid        PRIMARY KEY,
  email               citext      NOT NULL,
  phone_enc           bytea,
  phone_hash          bytea,
  display_name        text        CHECK (display_name IS NULL OR length(display_name) BETWEEN 1 AND 80),
  password_hash       text        NOT NULL CHECK (length(password_hash) BETWEEN 20 AND 512),
  status              text        NOT NULL DEFAULT 'pending_verification'
                      CHECK (status IN ('pending_verification', 'active', 'locked', 'disabled', 'deletion_pending', 'deleted')),
  email_verified_at   timestamptz,
  tz                  text        NOT NULL DEFAULT 'Asia/Kolkata' CHECK (length(tz) BETWEEN 1 AND 64),
  tier_cache          text        NOT NULL DEFAULT 'FREE' CHECK (tier_cache IN ('FREE', 'PREMIUM', 'VIP')),
  password_changed_at timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  version             integer     NOT NULL DEFAULT 0,
  CONSTRAINT accounts_email_key UNIQUE (email),
  CONSTRAINT accounts_email_shape CHECK (length(email) BETWEEN 3 AND 254 AND position('@' IN email) > 1),
  CONSTRAINT accounts_phone_pair CHECK ((phone_enc IS NULL) = (phone_hash IS NULL))
);
CREATE UNIQUE INDEX accounts_phone_hash ON identity.accounts (phone_hash) WHERE phone_hash IS NOT NULL;

ALTER TABLE identity.accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.accounts FORCE  ROW LEVEL SECURITY;
CREATE POLICY accounts_self ON identity.accounts FOR ALL TO identity_app
  USING      (id = (SELECT identity.app_account()))
  WITH CHECK (id = (SELECT identity.app_account()));
CREATE POLICY accounts_owner ON identity.accounts FOR ALL TO identity_owner USING (true) WITH CHECK (true);
-- INSERT is unused by the app (registration goes through auth_register) but RLS-bounded to the caller's own id.
GRANT SELECT, INSERT, UPDATE ON identity.accounts TO identity_app;

-- ---------------------------------------------------------------- per-account data keys (envelope encryption)
-- dek_enc = AES-256-GCM(KEK[kek_id], DEK, aad = account id). Destroying the DEK crypto-shreds phone_enc (P18).
CREATE TABLE identity.account_keys (
  account_id   uuid        PRIMARY KEY REFERENCES identity.accounts (id) ON DELETE CASCADE,
  dek_enc      bytea       NOT NULL,
  kek_id       text        NOT NULL CHECK (length(kek_id) BETWEEN 1 AND 32),
  created_at   timestamptz NOT NULL DEFAULT now(),
  destroyed_at timestamptz
);
ALTER TABLE identity.account_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.account_keys FORCE  ROW LEVEL SECURITY;
CREATE POLICY account_keys_tenant ON identity.account_keys FOR ALL TO identity_app
  USING      (account_id = (SELECT identity.app_account()))
  WITH CHECK (account_id = (SELECT identity.app_account()));
CREATE POLICY account_keys_owner ON identity.account_keys FOR ALL TO identity_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON identity.account_keys TO identity_app;

-- ---------------------------------------------------------------- email verification / password reset tokens
-- Only sha256(token) is stored; the raw token travels in the email link only.
CREATE TABLE identity.verification_tokens (
  id          uuid        PRIMARY KEY,
  account_id  uuid        NOT NULL REFERENCES identity.accounts (id) ON DELETE CASCADE,
  purpose     text        NOT NULL CHECK (purpose IN ('email_verify', 'password_reset')),
  token_hash  bytea       NOT NULL CHECK (length(token_hash) = 32),
  expires_at  timestamptz NOT NULL,
  used_at     timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT verification_tokens_token_hash_key UNIQUE (token_hash)
);
CREATE INDEX verification_tokens_recent ON identity.verification_tokens (account_id, purpose, created_at DESC);
ALTER TABLE identity.verification_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.verification_tokens FORCE  ROW LEVEL SECURITY;
CREATE POLICY verification_tokens_tenant ON identity.verification_tokens FOR ALL TO identity_app
  USING      (account_id = (SELECT identity.app_account()))
  WITH CHECK (account_id = (SELECT identity.app_account()));
CREATE POLICY verification_tokens_owner ON identity.verification_tokens FOR ALL TO identity_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON identity.verification_tokens TO identity_app;

-- ---------------------------------------------------------------- sessions (one per login, one refresh family each)
CREATE TABLE identity.sessions (
  id            uuid        PRIMARY KEY,
  account_id    uuid        NOT NULL REFERENCES identity.accounts (id) ON DELETE CASCADE,
  client_id     text        NOT NULL CHECK (length(client_id) BETWEEN 1 AND 64),
  device_label  text        CHECK (device_label IS NULL OR length(device_label) BETWEEN 1 AND 80),
  ua_hash       bytea,
  ip_first      inet,
  amr           text[]      NOT NULL DEFAULT '{pwd}',
  auth_time     timestamptz NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  last_seen_at  timestamptz NOT NULL DEFAULT now(),
  revoked_at    timestamptz,
  revoke_reason text        CHECK (revoke_reason IN ('logout', 'logout_all', 'user_revoked', 'password_change',
                                                     'password_reset', 'refresh_reuse', 'admin')),
  version       integer     NOT NULL DEFAULT 0,
  CONSTRAINT sessions_account_id_id_key UNIQUE (account_id, id),
  CONSTRAINT sessions_revoked_pair CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);
CREATE INDEX sessions_active_list ON identity.sessions (account_id, created_at DESC, id DESC) WHERE revoked_at IS NULL;
ALTER TABLE identity.sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.sessions FORCE  ROW LEVEL SECURITY;
CREATE POLICY sessions_tenant ON identity.sessions FOR ALL TO identity_app
  USING      (account_id = (SELECT identity.app_account()))
  WITH CHECK (account_id = (SELECT identity.app_account()));
CREATE POLICY sessions_owner ON identity.sessions FOR ALL TO identity_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON identity.sessions TO identity_app;

-- ---------------------------------------------------------------- refresh tokens (rotation + family reuse detection)
-- account_id is denormalised from the session so RLS needs no join. Only sha256(token) is stored.
CREATE TABLE identity.refresh_tokens (
  id                  uuid        PRIMARY KEY,
  account_id          uuid        NOT NULL,
  session_id          uuid        NOT NULL,
  family_id           uuid        NOT NULL,
  token_hash          bytea       NOT NULL CHECK (length(token_hash) = 32),
  parent_id           uuid,
  issued_at           timestamptz NOT NULL,
  expires_at          timestamptz NOT NULL,
  absolute_expires_at timestamptz NOT NULL,
  rotated_at          timestamptz,
  revoked_at          timestamptz,
  CONSTRAINT refresh_tokens_token_hash_key UNIQUE (token_hash),
  CONSTRAINT refresh_tokens_session_fk FOREIGN KEY (account_id, session_id)
    REFERENCES identity.sessions (account_id, id) ON DELETE CASCADE,
  CONSTRAINT refresh_tokens_expiry CHECK (expires_at > issued_at AND expires_at <= absolute_expires_at)
);
CREATE INDEX refresh_tokens_family ON identity.refresh_tokens (account_id, family_id);
CREATE INDEX refresh_tokens_session ON identity.refresh_tokens (account_id, session_id);
ALTER TABLE identity.refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.refresh_tokens FORCE  ROW LEVEL SECURITY;
CREATE POLICY refresh_tokens_tenant ON identity.refresh_tokens FOR ALL TO identity_app
  USING      (account_id = (SELECT identity.app_account()))
  WITH CHECK (account_id = (SELECT identity.app_account()));
CREATE POLICY refresh_tokens_owner ON identity.refresh_tokens FOR ALL TO identity_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON identity.refresh_tokens TO identity_app;

-- ---------------------------------------------------------------- JWT signing keys (global, MASTER_PLAN 7.1)
-- next -> active -> retired; retired keys stay published until every token they signed has expired.
-- private_enc = AES-256-GCM(KEK[kek_id], private JWK JSON, aad = 'signing-key:' || kid).
CREATE TABLE identity.signing_keys (
  kid          text        PRIMARY KEY CHECK (kid ~ '^[A-Za-z0-9_-]{8,64}$'),
  alg          text        NOT NULL CHECK (alg = 'ES256'),
  public_jwk   jsonb       NOT NULL,
  private_enc  bytea       NOT NULL,
  kek_id       text        NOT NULL CHECK (length(kek_id) BETWEEN 1 AND 32),
  status       text        NOT NULL CHECK (status IN ('next', 'active', 'retired')),
  created_at   timestamptz NOT NULL DEFAULT now(),
  activated_at timestamptz,
  retired_at   timestamptz,
  CONSTRAINT signing_keys_lifecycle CHECK (
    (status = 'next'    AND activated_at IS NULL     AND retired_at IS NULL) OR
    (status = 'active'  AND activated_at IS NOT NULL AND retired_at IS NULL) OR
    (status = 'retired' AND activated_at IS NOT NULL AND retired_at IS NOT NULL))
);
CREATE UNIQUE INDEX signing_keys_one_active ON identity.signing_keys ((true)) WHERE status = 'active';
CREATE UNIQUE INDEX signing_keys_one_next   ON identity.signing_keys ((true)) WHERE status = 'next';
ALTER TABLE identity.signing_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.signing_keys FORCE  ROW LEVEL SECURITY;
CREATE POLICY signing_keys_app ON identity.signing_keys FOR ALL TO identity_app USING (true) WITH CHECK (true);
CREATE POLICY signing_keys_owner ON identity.signing_keys FOR ALL TO identity_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.signing_keys TO identity_app;

-- ---------------------------------------------------------------- pre-authentication functions
-- Each: SECURITY DEFINER owned by identity_owner, search_path pinned, minimum columns, EXECUTE for identity_app only.

-- Login: the row needed to check a password. mfa_enabled is false until P05 adds TOTP (P05 replaces this function).
CREATE FUNCTION identity.auth_find_login(p_email citext)
RETURNS TABLE (account_id uuid, password_hash text, status text, mfa_enabled boolean, tz text, tier text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
  SELECT a.id, a.password_hash, a.status, false, a.tz, a.tier_cache
  FROM identity.accounts a
  WHERE a.email = p_email
$$;

-- Registration: creates the account unless the email is taken, and issues an email-verification token
-- (sha256 only) for a new or still-pending account — at most p_max_tokens per hour, older open tokens are
-- superseded. Never reveals anything to the client: the caller answers 202 either way and sends a different
-- email when created = false. An existing account's password is never changed here.
CREATE FUNCTION identity.auth_register(p_id uuid, p_email citext, p_password_hash text, p_tz text,
    p_display_name text, p_token_id uuid, p_token_hash bytea, p_token_expires timestamptz, p_max_tokens integer)
RETURNS TABLE (account_id uuid, created boolean, status text, token_issued boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
DECLARE
  v_id uuid;
  v_status text;
  v_created boolean;
  v_recent integer;
BEGIN
  INSERT INTO identity.accounts (id, email, password_hash, status, tz, display_name)
  VALUES (p_id, p_email, p_password_hash, 'pending_verification', p_tz, p_display_name)
  ON CONFLICT ON CONSTRAINT accounts_email_key DO NOTHING;
  v_created := FOUND;
  SELECT a.id, a.status INTO v_id, v_status FROM identity.accounts a WHERE a.email = p_email;
  IF v_status <> 'pending_verification' THEN
    RETURN QUERY SELECT v_id, v_created, v_status, false;
    RETURN;
  END IF;
  SELECT count(1) INTO v_recent FROM identity.verification_tokens t
  WHERE t.account_id = v_id AND t.purpose = 'email_verify' AND t.created_at > now() - interval '1 hour';
  IF v_recent >= p_max_tokens THEN
    RETURN QUERY SELECT v_id, v_created, v_status, false;
    RETURN;
  END IF;
  UPDATE identity.verification_tokens t SET expires_at = least(t.expires_at, now())
  WHERE t.account_id = v_id AND t.purpose = 'email_verify' AND t.used_at IS NULL;
  INSERT INTO identity.verification_tokens (id, account_id, purpose, token_hash, expires_at)
  VALUES (p_token_id, v_id, 'email_verify', p_token_hash, p_token_expires);
  RETURN QUERY SELECT v_id, v_created, v_status, true;
END
$$;

-- Refresh: the token row (and its session's state) by sha256(token). Rotation happens afterwards in a tenant
-- transaction (SELECT ... FOR UPDATE under RLS).
CREATE FUNCTION identity.auth_find_refresh(p_token_hash bytea)
RETURNS TABLE (token_id uuid, account_id uuid, session_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
  SELECT r.id, r.account_id, r.session_id FROM identity.refresh_tokens r WHERE r.token_hash = p_token_hash
$$;

-- Email verification: consumes an unexpired, unused token and activates a pending account, atomically.
-- Returns the account id and its version when the token was valid (also for an already active account), no row
-- otherwise.
CREATE FUNCTION identity.auth_verify_email(p_token_hash bytea)
RETURNS TABLE (account_id uuid, activated boolean, version integer)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
DECLARE
  v_account uuid;
  v_n integer;
BEGIN
  UPDATE identity.verification_tokens t SET used_at = now()
  WHERE t.token_hash = p_token_hash AND t.purpose = 'email_verify' AND t.used_at IS NULL AND t.expires_at > now()
  RETURNING t.account_id INTO v_account;
  IF v_account IS NULL THEN
    RETURN;
  END IF;
  UPDATE identity.accounts a
  SET status = 'active', email_verified_at = now(), updated_at = now(), version = a.version + 1
  WHERE a.id = v_account AND a.status = 'pending_verification';
  GET DIAGNOSTICS v_n = ROW_COUNT;
  RETURN QUERY SELECT v_account, v_n > 0, a.version FROM identity.accounts a WHERE a.id = v_account;
END
$$;

-- Password reset: consumes the token, sets the new hash and revokes every session and refresh token of the
-- account, atomically, and returns the revoked session ids (one identity.session.revoked event each). Only active
-- (or pending: a reset proves the mailbox) accounts can reset.
CREATE FUNCTION identity.auth_reset_password(p_token_hash bytea, p_password_hash text)
RETURNS TABLE (account_id uuid, revoked_sessions uuid[])
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
DECLARE
  v_account uuid;
  v_sessions uuid[];
BEGIN
  UPDATE identity.verification_tokens t SET used_at = now()
  WHERE t.token_hash = p_token_hash AND t.purpose = 'password_reset' AND t.used_at IS NULL AND t.expires_at > now()
  RETURNING t.account_id INTO v_account;
  IF v_account IS NULL THEN
    RETURN;
  END IF;
  UPDATE identity.accounts a
  SET password_hash = p_password_hash, password_changed_at = now(),
      status = CASE WHEN a.status = 'pending_verification' THEN 'active' ELSE a.status END,
      email_verified_at = COALESCE(a.email_verified_at, now()),
      updated_at = now(), version = a.version + 1
  WHERE a.id = v_account AND a.status IN ('active', 'pending_verification');
  IF NOT FOUND THEN
    RETURN;
  END IF;
  UPDATE identity.refresh_tokens r SET revoked_at = now()
  WHERE r.account_id = v_account AND r.revoked_at IS NULL;
  WITH revoked AS (
    UPDATE identity.sessions s SET revoked_at = now(), revoke_reason = 'password_reset', version = s.version + 1
    WHERE s.account_id = v_account AND s.revoked_at IS NULL
    RETURNING s.id)
  SELECT coalesce(array_agg(revoked.id ORDER BY revoked.id), '{}') INTO v_sessions FROM revoked;
  RETURN QUERY SELECT v_account, v_sessions;
END
$$;

-- Forgot password / resend verification: account id and status by email (no hash).
CREATE FUNCTION identity.auth_find_account(p_email citext)
RETURNS TABLE (account_id uuid, status text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
  SELECT a.id, a.status FROM identity.accounts a WHERE a.email = p_email
$$;

DO $$
DECLARE f text;
BEGIN
  FOREACH f IN ARRAY ARRAY[
      'identity.auth_find_login(citext)',
      'identity.auth_register(uuid, citext, text, text, text, uuid, bytea, timestamptz, integer)',
      'identity.auth_find_refresh(bytea)',
      'identity.auth_verify_email(bytea)',
      'identity.auth_reset_password(bytea, text)',
      'identity.auth_find_account(citext)'] LOOP
    EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f);
    EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO identity_app', f);
  END LOOP;
END
$$;
