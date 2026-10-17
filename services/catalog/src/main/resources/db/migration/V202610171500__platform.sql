-- P09: the shared platform SQL (dexter-db sql-templates 00..40) rendered for schema catalog.
-- Generated - do not edit by hand. PlatformMigrationTest fails when it drifts from SqlTemplates.renderAll("catalog").
-- ==== 00-base-functions.sql
-- MASTER_PLAN 3.2: schema, grants and the RLS context functions. Runs as catalog_owner.
CREATE SCHEMA IF NOT EXISTS catalog AUTHORIZATION catalog_owner;
REVOKE ALL ON SCHEMA catalog FROM PUBLIC;
GRANT USAGE ON SCHEMA catalog TO catalog_app, catalog_admin, catalog_relay, catalog_readonly;

-- NULL when unset or cleared, so every tenant policy matches zero rows (fails closed).
CREATE OR REPLACE FUNCTION catalog.app_account() RETURNS uuid
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT NULLIF(current_setting('app.account_id', true), '')::uuid $$;

CREATE OR REPLACE FUNCTION catalog.app_actor_role() RETURNS text
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT NULLIF(current_setting('app.actor_role', true), '') $$;

REVOKE ALL ON FUNCTION catalog.app_account() FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog.app_actor_role() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION catalog.app_account(), catalog.app_actor_role()
  TO catalog_app, catalog_admin, catalog_relay, catalog_readonly;

-- ==== 10-partitions.sql
-- MASTER_PLAN 3.6: range partitions by created_at, created ahead and dropped by age.
-- SECURITY DEFINER so catalog_relay can manage partitions without owning tables. Inputs are
-- validated identifiers only; all DDL uses format(%I/%L). Callers hold an advisory lock
-- (in.dexter.db.partition.PartitionManager) so only one instance runs them at a time.
-- Partition naming: <table>_pYYYYMMDD (day) or <table>_pYYYYMM (month), bounds in UTC.

CREATE OR REPLACE FUNCTION catalog.create_partitions_ahead(
    p_table text, p_grain text, p_ahead integer, p_anchor timestamptz DEFAULT now())
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
  v_start timestamp;
  v_lo    timestamp;
  v_hi    timestamp;
  v_name  text;
  v_made  integer := 0;
BEGIN
  IF p_table !~ '^[a-z_][a-z0-9_]{0,40}$' THEN RAISE EXCEPTION 'bad table name %', p_table; END IF;
  IF p_grain NOT IN ('day', 'month') THEN RAISE EXCEPTION 'bad grain %', p_grain; END IF;
  IF p_ahead < 0 OR p_ahead > 400 THEN RAISE EXCEPTION 'bad ahead %', p_ahead; END IF;
  IF to_regclass(format('%I.%I', 'catalog', p_table)) IS NULL THEN
    RAISE EXCEPTION 'no table catalog.%', p_table;
  END IF;
  v_start := date_trunc(p_grain, p_anchor AT TIME ZONE 'UTC');
  FOR i IN 0..p_ahead LOOP
    v_lo := v_start + make_interval(days => CASE WHEN p_grain = 'day' THEN i ELSE 0 END,
                                    months => CASE WHEN p_grain = 'month' THEN i ELSE 0 END);
    v_hi := v_lo + CASE WHEN p_grain = 'day' THEN interval '1 day' ELSE interval '1 month' END;
    v_name := p_table || '_p' || to_char(v_lo, CASE WHEN p_grain = 'day' THEN 'YYYYMMDD' ELSE 'YYYYMM' END);
    IF to_regclass(format('%I.%I', 'catalog', v_name)) IS NULL THEN
      EXECUTE format('CREATE TABLE %I.%I PARTITION OF %I.%I FOR VALUES FROM (%L) TO (%L)',
                     'catalog', v_name, 'catalog', p_table,
                     (v_lo AT TIME ZONE 'UTC'), (v_hi AT TIME ZONE 'UTC'));
      v_made := v_made + 1;
    END IF;
  END LOOP;
  RETURN v_made;
END
$$;

-- Drops partitions whose upper bound is <= p_cutoff. With p_guard_column set, a partition is only
-- dropped when no row has that column NULL (outbox: published_at), so nothing unpublished is lost.
CREATE OR REPLACE FUNCTION catalog.drop_partitions_older_than(
    p_table text, p_cutoff timestamptz, p_guard_column text DEFAULT NULL)
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
  r        record;
  v_suffix text;
  v_lo     timestamp;
  v_hi     timestamp;
  v_busy   boolean;
  v_dropped integer := 0;
BEGIN
  IF p_table !~ '^[a-z_][a-z0-9_]{0,40}$' THEN RAISE EXCEPTION 'bad table name %', p_table; END IF;
  IF p_guard_column IS NOT NULL AND p_guard_column !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
    RAISE EXCEPTION 'bad guard column %', p_guard_column;
  END IF;
  FOR r IN
    SELECT c.relname
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhrelid
    JOIN pg_class p ON p.oid = i.inhparent
    JOIN pg_namespace n ON n.oid = p.relnamespace
    WHERE n.nspname = 'catalog' AND p.relname = p_table
    ORDER BY c.relname
  LOOP
    v_suffix := substring(r.relname FROM '_p([0-9]{6,8})$');
    CONTINUE WHEN v_suffix IS NULL;
    IF length(v_suffix) = 8 THEN
      v_lo := to_timestamp(v_suffix, 'YYYYMMDD')::timestamp; v_hi := v_lo + interval '1 day';
    ELSE
      v_lo := to_timestamp(v_suffix || '01', 'YYYYMMDD')::timestamp; v_hi := v_lo + interval '1 month';
    END IF;
    CONTINUE WHEN (v_hi AT TIME ZONE 'UTC') > p_cutoff;
    IF p_guard_column IS NOT NULL THEN
      EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I WHERE %I IS NULL)', 'catalog', r.relname, p_guard_column)
        INTO v_busy;
      CONTINUE WHEN v_busy;
    END IF;
    EXECUTE format('DROP TABLE %I.%I', 'catalog', r.relname);
    v_dropped := v_dropped + 1;
  END LOOP;
  RETURN v_dropped;
END
$$;

REVOKE ALL ON FUNCTION catalog.create_partitions_ahead(text, text, integer, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog.drop_partitions_older_than(text, timestamptz, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION catalog.create_partitions_ahead(text, text, integer, timestamptz) TO catalog_relay;
GRANT EXECUTE ON FUNCTION catalog.drop_partitions_older_than(text, timestamptz, text) TO catalog_relay;

-- ==== 20-idempotency.sql
-- MASTER_PLAN 5.2: Idempotency-Key store. Not partitioned (keys must be unique across time).
-- recovery_point (P02): Mode B (5.3) stores the last completed atomic phase so a retry resumes there.
CREATE TABLE catalog.idempotency_keys (
  account_id       uuid        NOT NULL,
  idem_key         uuid        NOT NULL,
  request_method   text        NOT NULL,
  request_path     text        NOT NULL,
  request_hash     bytea       NOT NULL,
  state            text        NOT NULL CHECK (state IN ('in_progress', 'completed')),
  response_status  integer,
  response_body    jsonb,
  response_headers jsonb,
  locked_until     timestamptz,
  recovery_point   text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, idem_key)
);
CREATE INDEX idempotency_keys_created_brin ON catalog.idempotency_keys USING brin (created_at);

ALTER TABLE catalog.idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.idempotency_keys FORCE  ROW LEVEL SECURITY;

CREATE POLICY idempotency_keys_tenant ON catalog.idempotency_keys
  FOR ALL TO catalog_app
  USING      (account_id = (SELECT catalog.app_account()))
  WITH CHECK (account_id = (SELECT catalog.app_account()));

-- Purge job (relay role): may only see and delete expired keys.
CREATE POLICY idempotency_keys_purge_read ON catalog.idempotency_keys
  FOR SELECT TO catalog_relay
  USING (created_at < now() - interval '24 hours');
CREATE POLICY idempotency_keys_purge ON catalog.idempotency_keys
  FOR DELETE TO catalog_relay
  USING (created_at < now() - interval '24 hours');

GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.idempotency_keys TO catalog_app;
GRANT SELECT, DELETE ON catalog.idempotency_keys TO catalog_relay;

-- ==== 30-outbox.sql
-- MASTER_PLAN 6.3: transactional outbox, range-partitioned by created_at (daily).
-- rls:exempt outbox guarded by grants: app may only INSERT, relay may SELECT/UPDATE (no tenant reads)
CREATE TABLE catalog.outbox (
  id           uuid        NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  topic        text        NOT NULL,
  message_key  text        NOT NULL,
  event_type   text        NOT NULL,
  payload      jsonb       NOT NULL,
  headers      jsonb       NOT NULL DEFAULT '{}',
  published_at timestamptz,
  attempts     integer     NOT NULL DEFAULT 0,
  PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX outbox_unpublished ON catalog.outbox (created_at, id) WHERE published_at IS NULL;

CREATE OR REPLACE FUNCTION catalog.outbox_notify() RETURNS trigger
LANGUAGE plpgsql AS
$$ BEGIN PERFORM pg_notify('catalog_outbox_new', ''); RETURN NULL; END $$;

CREATE TRIGGER outbox_notify AFTER INSERT ON catalog.outbox
  FOR EACH STATEMENT EXECUTE FUNCTION catalog.outbox_notify();

GRANT INSERT ON catalog.outbox TO catalog_app;
GRANT SELECT, UPDATE ON catalog.outbox TO catalog_relay;

SELECT catalog.create_partitions_ahead('outbox', 'day', 3);

-- ==== 40-inbox.sql
-- MASTER_PLAN 6.4: consumer-side dedupe. One row per (consumer, event) applied.
-- rls:exempt inbox guarded by grants: holds event ids only, no tenant data
CREATE TABLE catalog.inbox (
  consumer    text        NOT NULL,
  event_id    uuid        NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);
CREATE INDEX inbox_received_brin ON catalog.inbox USING brin (received_at);

GRANT SELECT, INSERT ON catalog.inbox TO catalog_app;
GRANT SELECT, DELETE ON catalog.inbox TO catalog_relay;

