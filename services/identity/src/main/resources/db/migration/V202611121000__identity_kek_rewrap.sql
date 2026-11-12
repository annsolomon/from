-- P19 (ADR-0026): KEK re-wrap. After the active KEK changes (k1 -> k2, or k1 -> transit:dexter-kek), the job moves
-- every wrapped key to it so the old KEK can be retired (docs/runbooks/key-rotation.md). Two narrow SECURITY
-- DEFINER functions, EXECUTE for identity_app only:
--   kek_stale  lists wrapped keys not under the active KEK (optionally only those under p_from), oldest id first
--   kek_swap   replaces one wrapped key only if it is still under the KEK the caller read (compare-and-set: a
--              concurrent rotation or shred wins, the job simply sees false)
-- The plaintext never reaches the database: the service unwraps and re-wraps in memory (or in Vault).

CREATE FUNCTION identity.kek_stale(p_active text, p_from text, p_limit integer)
RETURNS TABLE (kind text, id text, kek_id text, sealed bytea)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
  (SELECT 'account_key', k.account_id::text, k.kek_id, k.dek_enc
     FROM identity.account_keys k
    WHERE k.kek_id <> p_active AND (p_from IS NULL OR k.kek_id = p_from) AND k.destroyed_at IS NULL
    ORDER BY k.account_id LIMIT p_limit)
  UNION ALL
  (SELECT 'signing_key', s.kid, s.kek_id, s.private_enc
     FROM identity.signing_keys s
    WHERE s.kek_id <> p_active AND (p_from IS NULL OR s.kek_id = p_from)
    ORDER BY s.kid LIMIT p_limit)
  UNION ALL
  (SELECT 'admin_totp', m.admin_id::text, m.secret_kek_id, m.secret_enc
     FROM identity.admin_mfa_totp m
    WHERE m.secret_kek_id <> p_active AND (p_from IS NULL OR m.secret_kek_id = p_from)
    ORDER BY m.admin_id LIMIT p_limit)
$$;

CREATE FUNCTION identity.kek_swap(p_kind text, p_id text, p_old_kek text, p_new_kek text, p_sealed bytea)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
BEGIN
  IF p_new_kek IS NULL OR length(p_new_kek) NOT BETWEEN 1 AND 32 OR p_sealed IS NULL OR length(p_sealed) = 0 THEN
    RAISE EXCEPTION 'kek_swap: bad new KEK id or empty ciphertext' USING ERRCODE = '22023';
  END IF;
  IF p_kind = 'account_key' THEN
    UPDATE identity.account_keys SET dek_enc = p_sealed, kek_id = p_new_kek
     WHERE account_id = p_id::uuid AND kek_id = p_old_kek AND destroyed_at IS NULL;
  ELSIF p_kind = 'signing_key' THEN
    UPDATE identity.signing_keys SET private_enc = p_sealed, kek_id = p_new_kek
     WHERE kid = p_id AND kek_id = p_old_kek;
  ELSIF p_kind = 'admin_totp' THEN
    UPDATE identity.admin_mfa_totp SET secret_enc = p_sealed, secret_kek_id = p_new_kek
     WHERE admin_id = p_id::uuid AND secret_kek_id = p_old_kek;
  ELSE
    RAISE EXCEPTION 'kek_swap: unknown kind %', p_kind USING ERRCODE = '22023';
  END IF;
  RETURN FOUND;
END
$$;

-- Rows per KEK (runbook: "is the old KEK still in use?"; the job's progress).
CREATE FUNCTION identity.kek_usage()
RETURNS TABLE (kind text, kek_id text, n bigint)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS
$$
  SELECT 'account_key', kek_id, count(*) FROM identity.account_keys WHERE destroyed_at IS NULL GROUP BY kek_id
  UNION ALL
  SELECT 'signing_key', kek_id, count(*) FROM identity.signing_keys GROUP BY kek_id
  UNION ALL
  SELECT 'admin_totp', secret_kek_id, count(*) FROM identity.admin_mfa_totp GROUP BY secret_kek_id
$$;

REVOKE ALL ON FUNCTION identity.kek_stale(text, text, integer), identity.kek_swap(text, text, text, text, bytea),
  identity.kek_usage() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity.kek_stale(text, text, integer), identity.kek_swap(text, text, text, text, bytea),
  identity.kek_usage() TO identity_app;
