-- P05 Identity B (MASTER_PLAN P05, 7.1, 7.2, 3.1). Runs as identity_owner after V202610170901.
--
-- Roles in play
--   identity_app    users (tenant = account) and admins acting on THEMSELVES (tenant = admin id): login, MFA,
--                   sessions, elevation.
--   identity_admin  admin endpoints acting on OTHERS (tenant = admin id, actor role from the verified token). Reads
--                   through explicit admin policies keyed on app_actor_role(); writes to admins only through the
--                   audited SECURITY DEFINER functions admin_create / admin_update (MASTER_PLAN 3.1).
--
-- Query -> index (BUILD_README §4, P05)
--   MFA state / verify / replay guard   mfa_totp by account_id            mfa_totp_pkey
--   recovery code use                   (account_id, code_hash)           recovery_codes_account_id_code_hash_key
--   recovery codes remaining            (account_id) WHERE used_at IS NULL recovery_codes_unused
--   admin login                         auth_find_admin_login(email)      admins_email_key
--   admin roles + permissions           admin_roles pkey (admin_id, role); role_permissions pkey (role, permission)
--   active elevation                    (admin_id, expires_at DESC) WHERE revoked_at IS NULL  elevations_active
--   admin refresh lookup                auth_find_admin_refresh(hash)     admin_refresh_tokens_token_hash_key
--   admin refresh / sessions            same shapes as P04 user tables    admin_refresh_tokens_*, admin_sessions_*
--   GET /v1/admin/admins (keyset)       ORDER BY created_at DESC, id DESC admins_list
--   account lookup (support)            accounts by email                 accounts_email_key
--   admin audit by admin                (admin_id, occurred_at DESC)      admin_audit_admin
--   admin audit by time                 occurred_at                       admin_audit_occurred_brin

-- ---------------------------------------------------------------- user TOTP (RFC 6238) + recovery codes
-- secret_enc: AES-GCM under the account's DEK (EnvelopeCipher, field 'totp'). last_used_step blocks replay of a
-- code within its 30-second step (MASTER_PLAN P05). confirmed_at NULL = enrolment pending.
CREATE TABLE identity.mfa_totp (
  account_id     uuid        PRIMARY KEY REFERENCES identity.accounts (id) ON DELETE CASCADE,
  secret_enc     bytea       NOT NULL,
  confirmed_at   timestamptz,
  last_used_step bigint      NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE identity.mfa_totp ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.mfa_totp FORCE  ROW LEVEL SECURITY;
CREATE POLICY mfa_totp_tenant ON identity.mfa_totp FOR ALL TO identity_app
  USING      (account_id = (SELECT identity.app_account()))
  WITH CHECK (account_id = (SELECT identity.app_account()));
CREATE POLICY mfa_totp_admin_read ON identity.mfa_totp FOR SELECT TO identity_admin
  USING ((SELECT identity.app_actor_role()) IN ('SUPPORT', 'FINANCE', 'SUPER_ADMIN'));
CREATE POLICY mfa_totp_owner ON identity.mfa_totp FOR ALL TO identity_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.mfa_totp TO identity_app;
GRANT SELECT ON identity.mfa_totp TO identity_admin;

-- code_hash = HMAC-SHA256(pepper, 'recovery:' || normalised code); each code works once.
CREATE TABLE identity.recovery_codes (
  account_id uuid        NOT NULL REFERENCES identity.accounts (id) ON DELETE CASCADE,
  id         uuid        NOT NULL,
  code_hash  bytea       NOT NULL CHECK (length(code_hash) = 32),
  used_at    timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, id),
  CONSTRAINT recovery_codes_account_id_code_hash_key UNIQUE (account_id, code_hash)
);
CREATE INDEX recovery_codes_unused ON identity.recovery_codes (account_id) WHERE used_at IS NULL;
ALTER TABLE identity.recovery_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.recovery_codes FORCE  ROW LEVEL SECURITY;
CREATE POLICY recovery_codes_tenant ON identity.recovery_codes FOR ALL TO identity_app
  USING      (account_id = (SELECT identity.app_account()))
  WITH CHECK (account_id = (SELECT identity.app_account()));
CREATE POLICY recovery_codes_owner ON identity.recovery_codes FOR ALL TO identity_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.recovery_codes TO identity_app;

-- The login lookup now reports confirmed MFA (P04 returned false).
CREATE OR REPLACE FUNCTION identity.auth_find_login(p_email citext)
RETURNS TABLE (account_id uuid, password_hash text, status text, mfa_enabled boolean, tz text, tier text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
  SELECT a.id, a.password_hash, a.status,
         EXISTS (SELECT 1 FROM identity.mfa_totp m WHERE m.account_id = a.id AND m.confirmed_at IS NOT NULL),
         a.tz, a.tier_cache
  FROM identity.accounts a
  WHERE a.email = p_email
$$;

-- Support lookup (GET /v1/admin/accounts?email=, audited): identity_admin may read accounts for these actor roles.
CREATE POLICY accounts_admin_read ON identity.accounts FOR SELECT TO identity_admin
  USING ((SELECT identity.app_actor_role()) IN ('SUPPORT', 'FINANCE', 'SUPER_ADMIN'));
GRANT SELECT ON identity.accounts TO identity_admin;

-- ---------------------------------------------------------------- RBAC catalogue (MASTER_PLAN 7.2)
-- Global reference data, managed only by migrations. SUPER_ADMIN is never a standing assignment: it is held only
-- through a time-boxed break-glass elevation (elevations, below).
CREATE TABLE identity.roles (
  code        text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z_]{1,31}$'),
  description text NOT NULL,
  standing    boolean NOT NULL
);
CREATE TABLE identity.permissions (
  code        text PRIMARY KEY CHECK (code ~ '^[a-z]+:[a-z_]+$'),
  description text NOT NULL
);
CREATE TABLE identity.role_permissions (
  role       text NOT NULL REFERENCES identity.roles (code),
  permission text NOT NULL REFERENCES identity.permissions (code),
  PRIMARY KEY (role, permission)
);
CREATE INDEX role_permissions_permission ON identity.role_permissions (permission);

INSERT INTO identity.roles (code, description, standing) VALUES
  ('SUPPORT', 'Customer support: account lookup, audit read', true),
  ('CATALOG_EDITOR', 'Catalog drafts, review queue, publishing', true),
  ('FINANCE', 'Billing read, refunds, account lookup', true),
  ('SUPER_ADMIN', 'Break-glass only: admin management and everything else', false);
INSERT INTO identity.permissions (code, description) VALUES
  ('accounts:read', 'Look up a user account (audited)'),
  ('accounts:write', 'Change a user account (support actions, P18)'),
  ('admins:read', 'List admins and their roles'),
  ('admins:write', 'Create, disable, re-role admins; reset admin MFA'),
  ('audit:read', 'Read the audit log (P18)'),
  ('catalog:read', 'Read catalog drafts and the review queue (P09/P10)'),
  ('catalog:write', 'Edit catalog drafts and ingestion sources (P09/P10)'),
  ('catalog:publish', 'Approve and publish catalog versions (P09/P10)'),
  ('billing:read', 'Read subscriptions, payments, invoices (P16)'),
  ('billing:refund', 'Issue refunds and credits (P16)');
INSERT INTO identity.role_permissions (role, permission) VALUES
  ('SUPPORT', 'accounts:read'), ('SUPPORT', 'audit:read'),
  ('CATALOG_EDITOR', 'catalog:read'), ('CATALOG_EDITOR', 'catalog:write'), ('CATALOG_EDITOR', 'catalog:publish'),
  ('FINANCE', 'accounts:read'), ('FINANCE', 'billing:read'), ('FINANCE', 'billing:refund');
INSERT INTO identity.role_permissions (role, permission) SELECT 'SUPER_ADMIN', code FROM identity.permissions;

GRANT SELECT ON identity.roles, identity.permissions, identity.role_permissions TO identity_app, identity_admin;
ALTER TABLE identity.roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.roles FORCE  ROW LEVEL SECURITY;
ALTER TABLE identity.permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.permissions FORCE  ROW LEVEL SECURITY;
ALTER TABLE identity.role_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.role_permissions FORCE  ROW LEVEL SECURITY;
CREATE POLICY roles_read ON identity.roles FOR SELECT TO identity_app, identity_admin USING (true);
CREATE POLICY roles_owner ON identity.roles FOR ALL TO identity_owner USING (true) WITH CHECK (true);
CREATE POLICY permissions_read ON identity.permissions FOR SELECT TO identity_app, identity_admin USING (true);
CREATE POLICY permissions_owner ON identity.permissions FOR ALL TO identity_owner USING (true) WITH CHECK (true);
CREATE POLICY role_permissions_read ON identity.role_permissions FOR SELECT TO identity_app, identity_admin USING (true);
CREATE POLICY role_permissions_owner ON identity.role_permissions FOR ALL TO identity_owner USING (true) WITH CHECK (true);

-- ---------------------------------------------------------------- admins (MASTER_PLAN P05)
-- password_hash NULL while an invite is open. MFA is mandatory (CHECK). admin_secret_* = TOTP secret wrapped by the
-- KEK (admins have no per-account DEK), aad 'admin-totp:<id>'.
CREATE TABLE identity.admins (
  id                   uuid        PRIMARY KEY,
  email                citext      NOT NULL,
  display_name         text        CHECK (display_name IS NULL OR length(display_name) BETWEEN 1 AND 80),
  password_hash        text        CHECK (password_hash IS NULL OR length(password_hash) BETWEEN 20 AND 512),
  status               text        NOT NULL DEFAULT 'invited' CHECK (status IN ('invited', 'active', 'disabled')),
  mfa_required         boolean     NOT NULL DEFAULT true CHECK (mfa_required),
  break_glass_eligible boolean     NOT NULL DEFAULT false,
  created_by           uuid,
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  version              integer     NOT NULL DEFAULT 0,
  CONSTRAINT admins_email_key UNIQUE (email),
  CONSTRAINT admins_email_shape CHECK (length(email) BETWEEN 3 AND 254 AND position('@' IN email) > 1),
  CONSTRAINT admins_active_has_password CHECK (status <> 'active' OR password_hash IS NOT NULL)
);
CREATE INDEX admins_list ON identity.admins (created_at DESC, id DESC);
ALTER TABLE identity.admins ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.admins FORCE  ROW LEVEL SECURITY;
CREATE POLICY admins_self ON identity.admins FOR ALL TO identity_app
  USING      (id = (SELECT identity.app_account()))
  WITH CHECK (id = (SELECT identity.app_account()));
CREATE POLICY admins_admin_read ON identity.admins FOR SELECT TO identity_admin
  USING ((SELECT identity.app_actor_role()) = 'SUPER_ADMIN');
CREATE POLICY admins_owner ON identity.admins FOR ALL TO identity_owner USING (true) WITH CHECK (true);
-- An admin may only change its own password hash (rehash on login); status, roles and eligibility change only
-- through admin_update (another SUPER_ADMIN, audited).
GRANT SELECT ON identity.admins TO identity_app;
GRANT UPDATE (password_hash, updated_at) ON identity.admins TO identity_app;
GRANT SELECT ON identity.admins TO identity_admin;

CREATE TABLE identity.admin_roles (
  admin_id   uuid        NOT NULL REFERENCES identity.admins (id) ON DELETE CASCADE,
  role       text        NOT NULL REFERENCES identity.roles (code),
  granted_by uuid,
  granted_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (admin_id, role)
);
CREATE INDEX admin_roles_role ON identity.admin_roles (role);
ALTER TABLE identity.admin_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.admin_roles FORCE  ROW LEVEL SECURITY;
CREATE POLICY admin_roles_self ON identity.admin_roles FOR SELECT TO identity_app
  USING (admin_id = (SELECT identity.app_account()));
CREATE POLICY admin_roles_admin_read ON identity.admin_roles FOR SELECT TO identity_admin
  USING ((SELECT identity.app_actor_role()) = 'SUPER_ADMIN');
CREATE POLICY admin_roles_owner ON identity.admin_roles FOR ALL TO identity_owner USING (true) WITH CHECK (true);
GRANT SELECT ON identity.admin_roles TO identity_app, identity_admin;

CREATE TABLE identity.admin_mfa_totp (
  admin_id       uuid        PRIMARY KEY REFERENCES identity.admins (id) ON DELETE CASCADE,
  secret_enc     bytea       NOT NULL,
  secret_kek_id  text        NOT NULL CHECK (length(secret_kek_id) BETWEEN 1 AND 32),
  confirmed_at   timestamptz,
  last_used_step bigint      NOT NULL DEFAULT 0,
  created_at     timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE identity.admin_mfa_totp ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.admin_mfa_totp FORCE  ROW LEVEL SECURITY;
CREATE POLICY admin_mfa_totp_self ON identity.admin_mfa_totp FOR ALL TO identity_app
  USING      (admin_id = (SELECT identity.app_account()))
  WITH CHECK (admin_id = (SELECT identity.app_account()));
CREATE POLICY admin_mfa_totp_owner ON identity.admin_mfa_totp FOR ALL TO identity_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.admin_mfa_totp TO identity_app;

-- Break-glass (MASTER_PLAN 7.2): an eligible admin holds SUPER_ADMIN for at most one hour, with a reason, after a
-- fresh MFA sign-in. approved_by stays NULL until a two-person rule is introduced.
CREATE TABLE identity.elevations (
  id          uuid        PRIMARY KEY,
  admin_id    uuid        NOT NULL REFERENCES identity.admins (id) ON DELETE CASCADE,
  role        text        NOT NULL DEFAULT 'SUPER_ADMIN' REFERENCES identity.roles (code),
  reason      text        NOT NULL CHECK (length(reason) BETWEEN 20 AND 500),
  approved_by uuid,
  starts_at   timestamptz NOT NULL,
  expires_at  timestamptz NOT NULL,
  revoked_at  timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT elevations_window CHECK (expires_at > starts_at AND expires_at <= starts_at + interval '1 hour')
);
CREATE INDEX elevations_active ON identity.elevations (admin_id, expires_at DESC) WHERE revoked_at IS NULL;
ALTER TABLE identity.elevations ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.elevations FORCE  ROW LEVEL SECURITY;
-- The database itself refuses an elevation for an admin that is not active and break-glass eligible.
CREATE POLICY elevations_self ON identity.elevations FOR ALL TO identity_app
  USING      (admin_id = (SELECT identity.app_account()))
  WITH CHECK (admin_id = (SELECT identity.app_account())
              AND EXISTS (SELECT 1 FROM identity.admins a
                          WHERE a.id = admin_id AND a.status = 'active' AND a.break_glass_eligible));
CREATE POLICY elevations_admin_read ON identity.elevations FOR SELECT TO identity_admin
  USING ((SELECT identity.app_actor_role()) = 'SUPER_ADMIN');
CREATE POLICY elevations_owner ON identity.elevations FOR ALL TO identity_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON identity.elevations TO identity_app;
GRANT SELECT ON identity.elevations TO identity_admin;

-- Admin console sessions + rotating refresh tokens (same rules as users, stricter windows in config).
CREATE TABLE identity.admin_sessions (
  id            uuid        PRIMARY KEY,
  admin_id      uuid        NOT NULL REFERENCES identity.admins (id) ON DELETE CASCADE,
  client_id     text        NOT NULL CHECK (length(client_id) BETWEEN 1 AND 64),
  ua_hash       bytea,
  ip_first      inet,
  amr           text[]      NOT NULL,
  auth_time     timestamptz NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  last_seen_at  timestamptz NOT NULL DEFAULT now(),
  revoked_at    timestamptz,
  revoke_reason text        CHECK (revoke_reason IN ('logout', 'refresh_reuse', 'disabled', 'mfa_reset', 'admin')),
  CONSTRAINT admin_sessions_admin_id_id_key UNIQUE (admin_id, id),
  CONSTRAINT admin_sessions_revoked_pair CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);
CREATE INDEX admin_sessions_active ON identity.admin_sessions (admin_id, created_at DESC) WHERE revoked_at IS NULL;
ALTER TABLE identity.admin_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.admin_sessions FORCE  ROW LEVEL SECURITY;
CREATE POLICY admin_sessions_self ON identity.admin_sessions FOR ALL TO identity_app
  USING      (admin_id = (SELECT identity.app_account()))
  WITH CHECK (admin_id = (SELECT identity.app_account()));
CREATE POLICY admin_sessions_owner ON identity.admin_sessions FOR ALL TO identity_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON identity.admin_sessions TO identity_app;

CREATE TABLE identity.admin_refresh_tokens (
  id                  uuid        PRIMARY KEY,
  admin_id            uuid        NOT NULL,
  session_id          uuid        NOT NULL,
  family_id           uuid        NOT NULL,
  token_hash          bytea       NOT NULL CHECK (length(token_hash) = 32),
  parent_id           uuid,
  issued_at           timestamptz NOT NULL,
  expires_at          timestamptz NOT NULL,
  absolute_expires_at timestamptz NOT NULL,
  rotated_at          timestamptz,
  revoked_at          timestamptz,
  CONSTRAINT admin_refresh_tokens_token_hash_key UNIQUE (token_hash),
  CONSTRAINT admin_refresh_tokens_session_fk FOREIGN KEY (admin_id, session_id)
    REFERENCES identity.admin_sessions (admin_id, id) ON DELETE CASCADE,
  CONSTRAINT admin_refresh_tokens_expiry CHECK (expires_at > issued_at AND expires_at <= absolute_expires_at)
);
CREATE INDEX admin_refresh_tokens_family ON identity.admin_refresh_tokens (admin_id, family_id);
CREATE INDEX admin_refresh_tokens_session ON identity.admin_refresh_tokens (admin_id, session_id);
ALTER TABLE identity.admin_refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.admin_refresh_tokens FORCE  ROW LEVEL SECURITY;
CREATE POLICY admin_refresh_tokens_self ON identity.admin_refresh_tokens FOR ALL TO identity_app
  USING      (admin_id = (SELECT identity.app_account()))
  WITH CHECK (admin_id = (SELECT identity.app_account()));
CREATE POLICY admin_refresh_tokens_owner ON identity.admin_refresh_tokens FOR ALL TO identity_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON identity.admin_refresh_tokens TO identity_app;

-- Invitations (only sha256 stored). Touched only by the definer functions.
CREATE TABLE identity.admin_invites (
  id         uuid        PRIMARY KEY,
  admin_id   uuid        NOT NULL REFERENCES identity.admins (id) ON DELETE CASCADE,
  token_hash bytea       NOT NULL CHECK (length(token_hash) = 32),
  expires_at timestamptz NOT NULL,
  used_at    timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT admin_invites_token_hash_key UNIQUE (token_hash)
);
CREATE INDEX admin_invites_admin ON identity.admin_invites (admin_id);
ALTER TABLE identity.admin_invites ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.admin_invites FORCE  ROW LEVEL SECURITY;
CREATE POLICY admin_invites_owner ON identity.admin_invites FOR ALL TO identity_owner USING (true) WITH CHECK (true);

-- Local, append-only admin audit trail (P18's hash-chained audit log consumes identity.admin.action events).
CREATE TABLE identity.admin_audit (
  id          uuid        PRIMARY KEY,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  admin_id    uuid        NOT NULL,
  action      text        NOT NULL CHECK (action ~ '^[A-Z][A-Z_]{2,47}$'),
  target_type text        NOT NULL CHECK (target_type IN ('account', 'admin', 'elevation', 'session')),
  target_id   uuid,
  details     jsonb       NOT NULL DEFAULT '{}'
);
CREATE INDEX admin_audit_admin ON identity.admin_audit (admin_id, occurred_at DESC);
CREATE INDEX admin_audit_occurred_brin ON identity.admin_audit USING brin (occurred_at);
ALTER TABLE identity.admin_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.admin_audit FORCE  ROW LEVEL SECURITY;
CREATE POLICY admin_audit_append_app ON identity.admin_audit FOR INSERT TO identity_app
  WITH CHECK (admin_id = (SELECT identity.app_account()));
CREATE POLICY admin_audit_append_admin ON identity.admin_audit FOR INSERT TO identity_admin
  WITH CHECK (admin_id = (SELECT identity.app_account()));
CREATE POLICY admin_audit_admin_read ON identity.admin_audit FOR SELECT TO identity_admin
  USING ((SELECT identity.app_actor_role()) IN ('SUPPORT', 'SUPER_ADMIN'));
CREATE POLICY admin_audit_owner ON identity.admin_audit FOR ALL TO identity_owner USING (true) WITH CHECK (true);
GRANT INSERT ON identity.admin_audit TO identity_app, identity_admin;
GRANT SELECT ON identity.admin_audit TO identity_admin;

-- Admin endpoints write events (outbox) and may use Idempotency-Key with the admin as tenant.
GRANT INSERT ON identity.outbox TO identity_admin;
CREATE POLICY idempotency_keys_admin ON identity.idempotency_keys FOR ALL TO identity_admin
  USING      (account_id = (SELECT identity.app_account()))
  WITH CHECK (account_id = (SELECT identity.app_account()));
GRANT SELECT, INSERT, UPDATE, DELETE ON identity.idempotency_keys TO identity_admin;

-- ---------------------------------------------------------------- admin pre-auth + audited write functions

CREATE FUNCTION identity.auth_find_admin_login(p_email citext)
RETURNS TABLE (admin_id uuid, password_hash text, status text, mfa_confirmed boolean)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
  SELECT a.id, a.password_hash, a.status,
         EXISTS (SELECT 1 FROM identity.admin_mfa_totp m WHERE m.admin_id = a.id AND m.confirmed_at IS NOT NULL)
  FROM identity.admins a
  WHERE a.email = p_email
$$;

-- Admin refresh: the token row by sha256(token); rotation follows in the admin's own tenant transaction.
CREATE FUNCTION identity.auth_find_admin_refresh(p_token_hash bytea)
RETURNS TABLE (token_id uuid, admin_id uuid, session_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
  SELECT r.id, r.admin_id, r.session_id FROM identity.admin_refresh_tokens r WHERE r.token_hash = p_token_hash
$$;

-- Invitation: single-use token → password set, status active (MFA setup follows at the first sign-in).
CREATE FUNCTION identity.admin_accept_invite(p_token_hash bytea, p_password_hash text)
RETURNS TABLE (admin_id uuid, email text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
DECLARE
  v_admin uuid;
BEGIN
  UPDATE identity.admin_invites i SET used_at = now()
  WHERE i.token_hash = p_token_hash AND i.used_at IS NULL AND i.expires_at > now()
  RETURNING i.admin_id INTO v_admin;
  IF v_admin IS NULL THEN
    RETURN;
  END IF;
  UPDATE identity.admins a SET password_hash = p_password_hash, status = 'active', updated_at = now(),
         version = a.version + 1
  WHERE a.id = v_admin AND a.status = 'invited';
  IF NOT FOUND THEN
    RETURN;
  END IF;
  INSERT INTO identity.admin_audit (id, admin_id, action, target_type, target_id)
  VALUES (gen_random_uuid(), v_admin, 'INVITE_ACCEPTED', 'admin', v_admin);
  RETURN QUERY SELECT a.id, a.email::text FROM identity.admins a WHERE a.id = v_admin;
END
$$;

-- Creates an invited admin (+ roles + invite token + audit row). Caller: identity_admin with actor SUPER_ADMIN.
-- Errors: DX403 not SUPER_ADMIN, DX409 email taken, DX400 unknown or non-standing role.
CREATE FUNCTION identity.admin_create(p_id uuid, p_email citext, p_display_name text, p_roles text[],
    p_break_glass boolean, p_invite_id uuid, p_invite_hash bytea, p_invite_expires timestamptz)
RETURNS TABLE (admin_id uuid, version integer, created_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
DECLARE
  v_actor uuid := identity.app_account();
BEGIN
  IF identity.app_actor_role() IS DISTINCT FROM 'SUPER_ADMIN' OR v_actor IS NULL THEN
    RAISE EXCEPTION 'SUPER_ADMIN required' USING ERRCODE = 'DX403';
  END IF;
  IF EXISTS (SELECT 1 FROM unnest(coalesce(p_roles, '{}')) r
             WHERE NOT EXISTS (SELECT 1 FROM identity.roles x WHERE x.code = r AND x.standing)) THEN
    RAISE EXCEPTION 'unknown or non-standing role' USING ERRCODE = 'DX400';
  END IF;
  BEGIN
    INSERT INTO identity.admins (id, email, display_name, status, break_glass_eligible, created_by)
    VALUES (p_id, p_email, p_display_name, 'invited', coalesce(p_break_glass, false), v_actor);
  EXCEPTION WHEN unique_violation THEN
    RAISE EXCEPTION 'email taken' USING ERRCODE = 'DX409';
  END;
  INSERT INTO identity.admin_roles (admin_id, role, granted_by)
  SELECT p_id, r, v_actor FROM unnest(coalesce(p_roles, '{}')) r;
  INSERT INTO identity.admin_invites (id, admin_id, token_hash, expires_at)
  VALUES (p_invite_id, p_id, p_invite_hash, p_invite_expires);
  INSERT INTO identity.admin_audit (id, admin_id, action, target_type, target_id, details)
  VALUES (gen_random_uuid(), v_actor, 'ADMIN_CREATED', 'admin', p_id,
          jsonb_build_object('roles', coalesce(p_roles, '{}'), 'breakGlassEligible', coalesce(p_break_glass, false)));
  RETURN QUERY SELECT a.id, a.version, a.created_at FROM identity.admins a WHERE a.id = p_id;
END
$$;

-- Versioned admin change (NULL argument = unchanged). Disabling or resetting MFA revokes the admin's sessions.
-- An admin can never change itself here (no self-lockout, no self-promotion).
-- Errors: DX403 not SUPER_ADMIN / self, DX404 unknown, DX412 stale version, DX400 bad status or role.
CREATE FUNCTION identity.admin_update(p_id uuid, p_expected_version integer, p_display_name text, p_status text,
    p_roles text[], p_break_glass boolean, p_reset_mfa boolean)
RETURNS TABLE (version integer, sessions_revoked integer)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
DECLARE
  v_actor uuid := identity.app_account();
  v_version integer;
  v_n integer := 0;
  v_reason text;
BEGIN
  IF identity.app_actor_role() IS DISTINCT FROM 'SUPER_ADMIN' OR v_actor IS NULL THEN
    RAISE EXCEPTION 'SUPER_ADMIN required' USING ERRCODE = 'DX403';
  END IF;
  IF p_id = v_actor THEN
    RAISE EXCEPTION 'admins cannot change themselves' USING ERRCODE = 'DX403';
  END IF;
  IF p_status IS NOT NULL AND p_status NOT IN ('active', 'disabled') THEN
    RAISE EXCEPTION 'bad status' USING ERRCODE = 'DX400';
  END IF;
  IF EXISTS (SELECT 1 FROM unnest(coalesce(p_roles, '{}')) r
             WHERE NOT EXISTS (SELECT 1 FROM identity.roles x WHERE x.code = r AND x.standing)) THEN
    RAISE EXCEPTION 'unknown or non-standing role' USING ERRCODE = 'DX400';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM identity.admins a WHERE a.id = p_id) THEN
    RAISE EXCEPTION 'unknown admin' USING ERRCODE = 'DX404';
  END IF;
  UPDATE identity.admins a SET
      display_name = CASE WHEN p_display_name IS NULL THEN a.display_name ELSE nullif(p_display_name, '') END,
      status = CASE WHEN p_status IS NULL OR a.status = 'invited' THEN a.status ELSE p_status END,
      break_glass_eligible = coalesce(p_break_glass, a.break_glass_eligible),
      updated_at = now(), version = a.version + 1
  WHERE a.id = p_id AND a.version = p_expected_version
  RETURNING a.version INTO v_version;
  IF v_version IS NULL THEN
    RAISE EXCEPTION 'stale version' USING ERRCODE = 'DX412';
  END IF;
  IF p_roles IS NOT NULL THEN
    DELETE FROM identity.admin_roles r WHERE r.admin_id = p_id AND r.role <> ALL (p_roles);
    INSERT INTO identity.admin_roles (admin_id, role, granted_by)
    SELECT p_id, x, v_actor FROM unnest(p_roles) x ON CONFLICT DO NOTHING;
  END IF;
  IF coalesce(p_reset_mfa, false) THEN
    DELETE FROM identity.admin_mfa_totp m WHERE m.admin_id = p_id;
  END IF;
  IF p_status = 'disabled' OR coalesce(p_reset_mfa, false) THEN
    v_reason := CASE WHEN p_status = 'disabled' THEN 'disabled' ELSE 'mfa_reset' END;
    UPDATE identity.admin_refresh_tokens t SET revoked_at = now() WHERE t.admin_id = p_id AND t.revoked_at IS NULL;
    UPDATE identity.admin_sessions s SET revoked_at = now(), revoke_reason = v_reason
    WHERE s.admin_id = p_id AND s.revoked_at IS NULL;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    UPDATE identity.elevations e SET revoked_at = now() WHERE e.admin_id = p_id AND e.revoked_at IS NULL;
  END IF;
  INSERT INTO identity.admin_audit (id, admin_id, action, target_type, target_id, details)
  VALUES (gen_random_uuid(), v_actor, 'ADMIN_UPDATED', 'admin', p_id, jsonb_strip_nulls(jsonb_build_object(
      'status', p_status, 'roles', p_roles, 'breakGlassEligible', p_break_glass, 'resetMfa', p_reset_mfa,
      'displayName', p_display_name)));
  RETURN QUERY SELECT v_version, v_n;
END
$$;

DO $$
DECLARE f text;
BEGIN
  FOREACH f IN ARRAY ARRAY[
      'identity.auth_find_admin_login(citext)',
      'identity.auth_find_admin_refresh(bytea)',
      'identity.admin_accept_invite(bytea, text)'] LOOP
    EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f);
    EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO identity_app', f);
  END LOOP;
  FOREACH f IN ARRAY ARRAY[
      'identity.admin_create(uuid, citext, text, text[], boolean, uuid, bytea, timestamptz)',
      'identity.admin_update(uuid, integer, text, text, text[], boolean, boolean)'] LOOP
    EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f);
    EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO identity_admin', f);
  END LOOP;
END
$$;
