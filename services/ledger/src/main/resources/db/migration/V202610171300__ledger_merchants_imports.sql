-- P08 Ledger B (MASTER_PLAN P08): merchants + aliases (global reference data, read-only for the app), CSV import
-- jobs and their row errors, the cross-tenant job claim for the import worker, and TEMP for COPY staging.
-- Runs as ledger_owner after V202610171201__ledger_core.
--
-- Query -> index (BUILD_README §4, P08)
--   resolve: exact alias                       UNIQUE (alias_normalized, merchant_id)                              merchant_aliases_key
--   resolve: exact compact alias / prefix      (alias_compact text_pattern_ops)                                    merchant_aliases_compact
--   resolve: trigram (% with threshold 0.45)   GIN (alias_compact gin_trgm_ops)                                    merchant_aliases_compact_trgm
--   autocomplete: prefix / contains / %        GIN (alias_normalized gin_trgm_ops)                                 merchant_aliases_trgm
--   merchant by id (join)                      pkey                                                                merchants_pkey
--   GET /v1/expenses/search (12-month bound)   expenses_list (account_id, occurred_at DESC, id DESC) + text recheck expenses_list
--   import job by id / list                    pkey; (account_id, created_at DESC, id DESC)                        import_jobs_account
--   worker claim (oldest queued)               (created_at) WHERE status IN ('queued','running')                   import_jobs_claim
--   import errors of a job                     PRIMARY KEY (job_id, row_no)                                        import_errors_pkey
--   rules of an account (priority order)       (account_id, priority, id)                                          categorization_rules_account (P07)

-- ---------------------------------------------------------------- merchants (global, seed/P10 write, app reads)
CREATE TABLE ledger.merchants (
  id               uuid         PRIMARY KEY,
  canonical_name   text         NOT NULL CHECK (length(canonical_name) BETWEEN 1 AND 120),
  normalized_name  text         NOT NULL CHECK (length(normalized_name) BETWEEN 1 AND 120),
  default_category public.ltree NOT NULL,
  logo_key         text         CHECK (logo_key IS NULL OR length(logo_key) <= 200),
  status           text         NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'hidden')),
  created_at       timestamptz  NOT NULL DEFAULT now()
);
ALTER TABLE ledger.merchants ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.merchants FORCE  ROW LEVEL SECURITY;
-- Reference data: every caller reads active merchants; only the owner (seed, P10 ingestion) writes.
CREATE POLICY merchants_read ON ledger.merchants FOR SELECT TO ledger_app USING (status = 'active');
CREATE POLICY merchants_owner ON ledger.merchants FOR ALL TO ledger_owner USING (true) WITH CHECK (true);
GRANT SELECT ON ledger.merchants TO ledger_app;

CREATE TABLE ledger.merchant_aliases (
  alias_normalized text    NOT NULL CHECK (length(alias_normalized) BETWEEN 1 AND 120),
  merchant_id      uuid    NOT NULL REFERENCES ledger.merchants (id) ON DELETE CASCADE,
  weight           integer NOT NULL DEFAULT 1000 CHECK (weight BETWEEN 1 AND 1000),
  -- "swiggy instamart" -> "swiggyinstamart": statement descriptors often drop spaces and truncate
  alias_compact    text    GENERATED ALWAYS AS (replace(alias_normalized, ' ', '')) STORED,
  CONSTRAINT merchant_aliases_key UNIQUE (alias_normalized, merchant_id)
);
CREATE INDEX merchant_aliases_trgm ON ledger.merchant_aliases USING gin (alias_normalized public.gin_trgm_ops);
CREATE INDEX merchant_aliases_compact_trgm ON ledger.merchant_aliases USING gin (alias_compact public.gin_trgm_ops);
CREATE INDEX merchant_aliases_compact ON ledger.merchant_aliases (alias_compact text_pattern_ops);
CREATE INDEX merchant_aliases_merchant ON ledger.merchant_aliases (merchant_id);
ALTER TABLE ledger.merchant_aliases ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.merchant_aliases FORCE  ROW LEVEL SECURITY;
CREATE POLICY merchant_aliases_read ON ledger.merchant_aliases FOR SELECT TO ledger_app USING (true);
CREATE POLICY merchant_aliases_owner ON ledger.merchant_aliases FOR ALL TO ledger_owner USING (true) WITH CHECK (true);
GRANT SELECT ON ledger.merchant_aliases TO ledger_app;

-- ---------------------------------------------------------------- CSV imports
CREATE TABLE ledger.import_jobs (
  id          uuid        PRIMARY KEY,
  account_id  uuid        NOT NULL,
  status      text        NOT NULL CHECK (status IN ('awaiting_upload', 'queued', 'running', 'completed', 'failed')),
  file_key    text        NOT NULL CHECK (length(file_key) BETWEEN 1 AND 300),
  file_name   text        CHECK (file_name IS NULL OR length(file_name) BETWEEN 1 AND 200),
  total       integer     NOT NULL DEFAULT 0 CHECK (total >= 0),
  ok          integer     NOT NULL DEFAULT 0 CHECK (ok >= 0),
  duplicates  integer     NOT NULL DEFAULT 0 CHECK (duplicates >= 0),
  failed      integer     NOT NULL DEFAULT 0 CHECK (failed >= 0),
  error_code  text        CHECK (error_code IS NULL OR length(error_code) <= 60),
  error_detail text       CHECK (error_detail IS NULL OR length(error_detail) <= 500),
  attempts    integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  created_at  timestamptz NOT NULL DEFAULT now(),
  started_at  timestamptz,
  finished_at timestamptz,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  version     integer     NOT NULL DEFAULT 0,
  CONSTRAINT import_jobs_counts CHECK (ok + failed <= total AND duplicates <= ok)
);
CREATE INDEX import_jobs_account ON ledger.import_jobs (account_id, created_at DESC, id DESC);
CREATE INDEX import_jobs_claim ON ledger.import_jobs (created_at) WHERE status IN ('queued', 'running');
ALTER TABLE ledger.import_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.import_jobs FORCE  ROW LEVEL SECURITY;
CREATE POLICY import_jobs_tenant ON ledger.import_jobs FOR ALL TO ledger_app
  USING      (account_id = (SELECT ledger.app_account()))
  WITH CHECK (account_id = (SELECT ledger.app_account()));
CREATE POLICY import_jobs_owner ON ledger.import_jobs FOR ALL TO ledger_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON ledger.import_jobs TO ledger_app;

CREATE TABLE ledger.import_errors (
  job_id     uuid    NOT NULL REFERENCES ledger.import_jobs (id) ON DELETE CASCADE,
  account_id uuid    NOT NULL,
  row_no     integer NOT NULL CHECK (row_no >= 1),
  code       text    NOT NULL CHECK (length(code) BETWEEN 1 AND 60),
  detail     text    CHECK (detail IS NULL OR length(detail) <= 300),
  PRIMARY KEY (job_id, row_no)
);
ALTER TABLE ledger.import_errors ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.import_errors FORCE  ROW LEVEL SECURITY;
CREATE POLICY import_errors_tenant ON ledger.import_errors FOR ALL TO ledger_app
  USING      (account_id = (SELECT ledger.app_account()))
  WITH CHECK (account_id = (SELECT ledger.app_account()));
CREATE POLICY import_errors_owner ON ledger.import_errors FOR ALL TO ledger_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON ledger.import_errors TO ledger_app;

-- The worker claims the oldest queued job of any account (no tenant context yet), or a running job whose worker
-- died (started more than p_stale ago). SKIP LOCKED lets several instances claim in parallel. Returns at most one
-- row; the worker then processes it inside that account's tenant transaction.
CREATE FUNCTION ledger.import_claim_next(p_stale interval, p_max_attempts integer)
RETURNS TABLE (job_id uuid, account_id uuid, file_key text, attempts integer)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
#variable_conflict use_column
DECLARE
  v_id uuid;
BEGIN
  IF p_stale < interval '1 minute' OR p_max_attempts NOT BETWEEN 1 AND 10 THEN
    RAISE EXCEPTION 'bad claim arguments';
  END IF;
  -- give up on jobs that keep dying
  UPDATE ledger.import_jobs j
     SET status = 'failed', error_code = 'IMPORT_ABANDONED', finished_at = now(), updated_at = now(),
         version = j.version + 1
   WHERE j.status = 'running' AND j.started_at < now() - p_stale AND j.attempts >= p_max_attempts;
  SELECT j.id INTO v_id
    FROM ledger.import_jobs j
   WHERE j.status = 'queued' OR (j.status = 'running' AND j.started_at < now() - p_stale)
   ORDER BY j.created_at
   LIMIT 1
   FOR UPDATE SKIP LOCKED;
  IF v_id IS NULL THEN
    RETURN;
  END IF;
  RETURN QUERY
  UPDATE ledger.import_jobs j
     SET status = 'running', started_at = now(), attempts = j.attempts + 1, updated_at = now(),
         version = j.version + 1
   WHERE j.id = v_id
  RETURNING j.id, j.account_id, j.file_key, j.attempts;
END
$$;
REVOKE ALL ON FUNCTION ledger.import_claim_next(interval, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ledger.import_claim_next(interval, integer) TO ledger_app;

-- The import stages each CSV with COPY into a session-private temporary table (COPY FROM is not allowed on tables
-- with row-level security, and a temp table is invisible to other sessions and dropped at commit).
DO $$
BEGIN
  EXECUTE format('GRANT TEMPORARY ON DATABASE %I TO ledger_app', current_database());
END
$$;
