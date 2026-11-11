-- P18 (MASTER_PLAN P18, ADR-0024): identity's part in the privacy saga. libs/dexter-privacy calls these functions as
-- identity_app when the audit service's saga asks (audit.privacy.requested), then acknowledges with
-- identity.privacy.completed in the same transaction (outbox).
--
-- The account's tables are found, not listed: every table of the schema with an account_id column. A table added in
-- a later phase is erased and exported without touching this file — and the functions refuse to run if the owner
-- cannot see such a table's rows (FORCE RLS without an owner policy would make "0 rows left" a lie).
-- Anonymized, not deleted: (none).
-- Kept by erase: account_keys.

-- The owner reads and writes every account's rows of an account table (the SECURITY DEFINER functions below run as
-- identity_owner, which FORCE RLS binds like any role): add the policy where a table has none yet.
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT c.relname FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'account_id' AND NOT a.attisdropped
     WHERE n.nspname = 'identity' AND c.relkind IN ('r', 'p') AND NOT c.relispartition AND c.relrowsecurity
       AND NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid AND 'identity_owner'::regrole = ANY (p.polroles))
  LOOP
    EXECUTE format('CREATE POLICY %I ON identity.%I FOR ALL TO identity_owner USING (true) WITH CHECK (true)',
                   r.relname || '_privacy_owner', r.relname);
  END LOOP;
END $$;

-- The account tables, by name.
CREATE OR REPLACE FUNCTION identity.privacy_tables() RETURNS text[]
LANGUAGE sql STABLE SET search_path = pg_catalog AS
$$
  SELECT coalesce(array_agg(c.relname::text ORDER BY c.relname), '{}')
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'account_id' AND NOT a.attisdropped
   WHERE n.nspname = 'identity' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
$$;

-- Refuses when an account table under RLS has no policy for the owner (its rows would be invisible here).
CREATE OR REPLACE FUNCTION identity.privacy_assert_visible() RETURNS void
LANGUAGE plpgsql STABLE SET search_path = pg_catalog AS
$$
DECLARE v_blind text[];
BEGIN
  SELECT array_agg(c.relname::text ORDER BY c.relname) INTO v_blind
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'identity' AND c.relname = ANY (identity.privacy_tables()) AND c.relrowsecurity
     AND NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid AND 'identity_owner'::regrole = ANY (p.polroles));
  IF v_blind IS NOT NULL THEN
    RAISE EXCEPTION 'privacy: identity_owner has no policy on %', v_blind USING ERRCODE = 'insufficient_privilege';
  END IF;
END $$;

-- Secrets never leave in an export: *_enc / *_hash columns (ciphertexts, digests) and anything named like a secret.
CREATE OR REPLACE FUNCTION identity.privacy_scrub(p_row jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog AS
$$
  SELECT coalesce(jsonb_object_agg(k, v), '{}'::jsonb)
    FROM jsonb_each(p_row) AS e(k, v)
   WHERE k !~ '(_enc|_hash|secret|password|token_digest)$'
$$;

-- How many of the account's rows the service still holds (0 after erase).
CREATE OR REPLACE FUNCTION identity.privacy_remaining(p_account uuid) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE t text; n bigint; total bigint := 0;
BEGIN
  PERFORM identity.privacy_assert_visible();
  FOREACH t IN ARRAY identity.privacy_tables() LOOP
    EXECUTE format('SELECT count(*) FROM identity.%I WHERE account_id = $1', t) INTO n USING p_account;
    total := total + n;
  END LOOP;
  RETURN total;
END $$;

-- Erase: re-key the anonymized tables to one fresh random id, then delete the account's rows from every other
-- account table. The order is found by retrying a table whose delete a foreign key refused until no table is left
-- (each statement in its own subtransaction); no progress in a pass is a schema bug and raises. Published outbox
-- rows keyed by the account go too; unpublished ones are still on their way and stay.
CREATE OR REPLACE FUNCTION identity.privacy_erase(p_account uuid) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
  v_anon text[] := '{}'::text[];
  v_keep text[] := ARRAY['account_keys']::text[];
  v_new  uuid := gen_random_uuid();
  v_left text[];
  v_next text[];
  t text; n bigint; total bigint := 0;
BEGIN
  PERFORM identity.privacy_assert_visible();
  FOREACH t IN ARRAY v_anon LOOP
    EXECUTE format('UPDATE identity.%I SET account_id = $1 WHERE account_id = $2', t) USING v_new, p_account;
    GET DIAGNOSTICS n = ROW_COUNT;
    total := total + n;
  END LOOP;
  v_left := ARRAY(SELECT x FROM unnest(identity.privacy_tables()) AS x WHERE x <> ALL (v_anon) AND x <> ALL (v_keep));
  WHILE cardinality(v_left) > 0 LOOP
    v_next := '{}';
    FOREACH t IN ARRAY v_left LOOP
      BEGIN
        EXECUTE format('DELETE FROM identity.%I WHERE account_id = $1', t) USING p_account;
        GET DIAGNOSTICS n = ROW_COUNT;
        total := total + n;
      EXCEPTION WHEN foreign_key_violation THEN
        v_next := v_next || t;
      END;
    END LOOP;
    IF cardinality(v_next) = cardinality(v_left) THEN
      RAISE EXCEPTION 'privacy: no foreign-key-safe order for %', v_next;
    END IF;
    v_left := v_next;
  END LOOP;
  DELETE FROM identity.outbox WHERE message_key = p_account::text AND published_at IS NOT NULL;
  RETURN total;
END $$;

-- Export: {"<table>": [rows...]} for every account table but the skipped ones, each row scrubbed.
CREATE OR REPLACE FUNCTION identity.privacy_export(p_account uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
  v_skip text[] := ARRAY['idempotency_keys', 'account_keys', 'refresh_tokens', 'mfa_totp', 'recovery_codes', 'verification_tokens']::text[];
  v_out  jsonb := '{}'::jsonb;
  v_part jsonb;
  t text;
BEGIN
  PERFORM identity.privacy_assert_visible();
  FOREACH t IN ARRAY identity.privacy_tables() LOOP
    CONTINUE WHEN t = ANY (v_skip);
    EXECUTE format('SELECT coalesce(jsonb_agg(identity.privacy_scrub(to_jsonb(x))), ''[]''::jsonb) FROM identity.%I x '
                   'WHERE account_id = $1', t) INTO v_part USING p_account;
    v_out := v_out || jsonb_build_object(t, v_part);
  END LOOP;
  v_out := v_out || jsonb_build_object('accounts', identity.privacy_export_account(p_account));
  RETURN v_out;
END $$;

-- The audit permission P18 adds (audit:read exists since P05: SUPPORT, SUPER_ADMIN). Expand-only.
--   audit:verify  run the chain verification, resume a failed privacy request          SUPER_ADMIN
INSERT INTO identity.permissions (code, description) VALUES
  ('audit:verify', 'Verify the audit hash chain and resume a failed privacy request (P18)');
INSERT INTO identity.role_permissions (role, permission) VALUES ('SUPER_ADMIN', 'audit:verify');

-- identity's own steps. A deletion is a revocation reason now.
ALTER TABLE identity.sessions DROP CONSTRAINT sessions_revoke_reason_check;
ALTER TABLE identity.sessions ADD CONSTRAINT sessions_revoke_reason_check CHECK (revoke_reason IN ('logout',
  'logout_all', 'user_revoked', 'password_change', 'password_reset', 'refresh_reuse', 'admin', 'account_deletion'));

-- Step 1 (freeze): deletion_pending (sign-in and refresh refuse anything but active) and every session revoked.
-- Returns the sessions revoked. A deleted account stays deleted.
CREATE OR REPLACE FUNCTION identity.privacy_freeze(p_account uuid) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE n bigint;
BEGIN
  UPDATE identity.accounts SET status = 'deletion_pending', updated_at = now(), version = version + 1
   WHERE id = p_account AND status NOT IN ('deletion_pending', 'deleted');
  UPDATE identity.sessions SET revoked_at = now(), revoke_reason = 'account_deletion', version = version + 1
   WHERE account_id = p_account AND revoked_at IS NULL;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;

-- Step 3 (shred): the account's data key is destroyed (crypto-shredding, MASTER_PLAN 7.4): every ciphertext sealed
-- under it, in any copy or backup, is unreadable from here on. The account row stays as a tombstone so the id is
-- never reused: no email, phone, name or password left, status deleted. Returns the keys destroyed.
CREATE OR REPLACE FUNCTION identity.privacy_shred(p_account uuid) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE n bigint;
BEGIN
  UPDATE identity.account_keys SET dek_enc = '\x'::bytea, destroyed_at = now()
   WHERE account_id = p_account AND destroyed_at IS NULL;
  GET DIAGNOSTICS n = ROW_COUNT;
  UPDATE identity.accounts
     SET email = id::text || '@deleted.invalid', phone_enc = NULL, phone_hash = NULL, display_name = NULL,
         password_hash = '!deleted:' || encode(sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'hex'),
         status = 'deleted', updated_at = now(), version = version + 1
   WHERE id = p_account AND status <> 'deleted';
  RETURN n;
END $$;

-- identity's count: the account tables (the key row only while its key exists) plus the account row until it is
-- a tombstone.
CREATE OR REPLACE FUNCTION identity.privacy_remaining(p_account uuid) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE t text; n bigint; total bigint := 0;
BEGIN
  PERFORM identity.privacy_assert_visible();
  FOREACH t IN ARRAY identity.privacy_tables() LOOP
    CONTINUE WHEN t = 'account_keys';
    EXECUTE format('SELECT count(*) FROM identity.%I WHERE account_id = $1', t) INTO n USING p_account;
    total := total + n;
  END LOOP;
  SELECT total + count(*) INTO total FROM identity.account_keys
   WHERE account_id = p_account AND destroyed_at IS NULL;
  SELECT total + count(*) INTO total FROM identity.accounts WHERE id = p_account AND status <> 'deleted';
  RETURN total;
END $$;

-- identity's export adds the account row itself (scrubbed: no password hash, no phone ciphertext).
CREATE OR REPLACE FUNCTION identity.privacy_export_account(p_account uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
  SELECT coalesce(jsonb_agg(identity.privacy_scrub(to_jsonb(a))), '[]'::jsonb) FROM identity.accounts a
   WHERE a.id = p_account
$$;
REVOKE ALL ON FUNCTION identity.privacy_export_account(uuid) FROM PUBLIC;

REVOKE ALL ON FUNCTION identity.privacy_remaining(uuid), identity.privacy_erase(uuid), identity.privacy_export(uuid), identity.privacy_freeze(uuid), identity.privacy_shred(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity.privacy_remaining(uuid), identity.privacy_erase(uuid), identity.privacy_export(uuid), identity.privacy_freeze(uuid), identity.privacy_shred(uuid) TO identity_app;
