-- P18 (MASTER_PLAN P18, ADR-0024): audit's own part in the privacy saga, the same functions every participant has.
-- The saga calls them itself as its last step on a deletion (ADR-0024 §4) and for audit's part of an export.
-- privacy_requests rows are not erased but made pseudonymous by the saga (account_id NULL, subject_ref = HMAC of
-- the account id); privacy_remaining counts them until then.
--
-- The account's tables are found, not listed: every table of the schema with an account_id column. A table added in
-- a later phase is erased and exported without touching this file — and the functions refuse to run if the owner
-- cannot see such a table's rows (FORCE RLS without an owner policy would make "0 rows left" a lie).
-- Anonymized, not deleted: (none).
-- Kept by erase: privacy_requests.

-- The owner reads and writes every account's rows of an account table (the SECURITY DEFINER functions below run as
-- audit_owner, which FORCE RLS binds like any role): add the policy where a table has none yet.
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT c.relname FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'account_id' AND NOT a.attisdropped
     WHERE n.nspname = 'audit' AND c.relkind IN ('r', 'p') AND NOT c.relispartition AND c.relrowsecurity
       AND NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid AND 'audit_owner'::regrole = ANY (p.polroles))
  LOOP
    EXECUTE format('CREATE POLICY %I ON audit.%I FOR ALL TO audit_owner USING (true) WITH CHECK (true)',
                   r.relname || '_privacy_owner', r.relname);
  END LOOP;
END $$;

-- The account tables, by name.
CREATE OR REPLACE FUNCTION audit.privacy_tables() RETURNS text[]
LANGUAGE sql STABLE SET search_path = pg_catalog AS
$$
  SELECT coalesce(array_agg(c.relname::text ORDER BY c.relname), '{}')
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'account_id' AND NOT a.attisdropped
   WHERE n.nspname = 'audit' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
$$;

-- Refuses when an account table under RLS has no policy for the owner (its rows would be invisible here).
CREATE OR REPLACE FUNCTION audit.privacy_assert_visible() RETURNS void
LANGUAGE plpgsql STABLE SET search_path = pg_catalog AS
$$
DECLARE v_blind text[];
BEGIN
  SELECT array_agg(c.relname::text ORDER BY c.relname) INTO v_blind
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'audit' AND c.relname = ANY (audit.privacy_tables()) AND c.relrowsecurity
     AND NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid AND 'audit_owner'::regrole = ANY (p.polroles));
  IF v_blind IS NOT NULL THEN
    RAISE EXCEPTION 'privacy: audit_owner has no policy on %', v_blind USING ERRCODE = 'insufficient_privilege';
  END IF;
END $$;

-- Secrets never leave in an export: *_enc / *_hash columns (ciphertexts, digests) and anything named like a secret.
CREATE OR REPLACE FUNCTION audit.privacy_scrub(p_row jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog AS
$$
  SELECT coalesce(jsonb_object_agg(k, v), '{}'::jsonb)
    FROM jsonb_each(p_row) AS e(k, v)
   WHERE k !~ '(_enc|_hash|secret|password|token_digest)$'
$$;

-- How many of the account's rows the service still holds (0 after erase).
CREATE OR REPLACE FUNCTION audit.privacy_remaining(p_account uuid) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE t text; n bigint; total bigint := 0;
BEGIN
  PERFORM audit.privacy_assert_visible();
  FOREACH t IN ARRAY audit.privacy_tables() LOOP
    EXECUTE format('SELECT count(*) FROM audit.%I WHERE account_id = $1', t) INTO n USING p_account;
    total := total + n;
  END LOOP;
  RETURN total;
END $$;

-- Erase: re-key the anonymized tables to one fresh random id, then delete the account's rows from every other
-- account table. The order is found by retrying a table whose delete a foreign key refused until no table is left
-- (each statement in its own subtransaction); no progress in a pass is a schema bug and raises. Published outbox
-- rows keyed by the account go too; unpublished ones are still on their way and stay.
CREATE OR REPLACE FUNCTION audit.privacy_erase(p_account uuid) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
  v_anon text[] := '{}'::text[];
  v_keep text[] := ARRAY['privacy_requests']::text[];
  v_new  uuid := gen_random_uuid();
  v_left text[];
  v_next text[];
  t text; n bigint; total bigint := 0;
BEGIN
  PERFORM audit.privacy_assert_visible();
  FOREACH t IN ARRAY v_anon LOOP
    EXECUTE format('UPDATE audit.%I SET account_id = $1 WHERE account_id = $2', t) USING v_new, p_account;
    GET DIAGNOSTICS n = ROW_COUNT;
    total := total + n;
  END LOOP;
  v_left := ARRAY(SELECT x FROM unnest(audit.privacy_tables()) AS x WHERE x <> ALL (v_anon) AND x <> ALL (v_keep));
  WHILE cardinality(v_left) > 0 LOOP
    v_next := '{}';
    FOREACH t IN ARRAY v_left LOOP
      BEGIN
        EXECUTE format('DELETE FROM audit.%I WHERE account_id = $1', t) USING p_account;
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
  DELETE FROM audit.outbox WHERE message_key = p_account::text AND published_at IS NOT NULL;
  RETURN total;
END $$;

-- Export: {"<table>": [rows...]} for every account table but the skipped ones, each row scrubbed.
CREATE OR REPLACE FUNCTION audit.privacy_export(p_account uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
  v_skip text[] := ARRAY['idempotency_keys', 'privacy_requests']::text[];
  v_out  jsonb := '{}'::jsonb;
  v_part jsonb;
  t text;
BEGIN
  PERFORM audit.privacy_assert_visible();
  FOREACH t IN ARRAY audit.privacy_tables() LOOP
    CONTINUE WHEN t = ANY (v_skip);
    EXECUTE format('SELECT coalesce(jsonb_agg(audit.privacy_scrub(to_jsonb(x))), ''[]''::jsonb) FROM audit.%I x '
                   'WHERE account_id = $1', t) INTO v_part USING p_account;
    v_out := v_out || jsonb_build_object(t, v_part);
  END LOOP;
  RETURN v_out;
END $$;

REVOKE ALL ON FUNCTION audit.privacy_remaining(uuid), audit.privacy_erase(uuid), audit.privacy_export(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.privacy_remaining(uuid), audit.privacy_erase(uuid), audit.privacy_export(uuid) TO audit_app;
