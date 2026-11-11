-- P18 Audit (MASTER_PLAN P18, ADR-0024): the hash-chained audit log and its anchors, activity analytics, the
-- notification outbox and inbox, notification preferences, and the privacy saga's requests.
-- Runs as audit_owner after V202611111100__platform.
--   audit_app    user requests (the inbox, preferences, privacy requests) and the SYSTEM actor: the event consumers,
--                the notification worker, the privacy saga, the anchor job (system work that spans accounts)
--   audit_admin  /v1/admin/audit*, /v1/admin/activity/**: operators (SUPPORT, SUPER_ADMIN) read; nobody writes
--   audit_relay  outbox, partitions of audit_log (monthly) and activity_events (daily, 90 days), purges
--
-- Query -> index (BUILD_README §4, P18)
--   append: the chain head under the advisory lock          PRIMARY KEY (id) of the one-row chain_head       chain_head_pkey
--   GET /v1/admin/audit, newest first                       (seq DESC) per monthly partition                 audit_log_pkey
--   ... filtered by actor / target / action                 (actor_id, seq) / (target_type, target_id, seq)  audit_log_actor / _target / _action
--                                                          / (action, seq)
--   ... a time window                                       BRIN (occurred_at) + partition pruning           audit_log_occurred_brin
--   :verify-chain, in seq order from a seq                  (seq) per partition                              audit_log_pkey
--   anchors in seq order                                    PRIMARY KEY (seq)                                audit_anchors_pkey
--   activity: one event once                                PRIMARY KEY (occurred_at, id) per daily partition activity_events_pkey
--   dashboard: hours in a window                            PRIMARY KEY (hour, service, route_class)         activity_hourly_pkey
--   GET /v1/notifications, newest first                     (account_id, created_at DESC, id DESC) in-app    notifications_inbox
--   ... unread only                                         same, WHERE read_at IS NULL                      notifications_unread
--   one notification per event per channel                  UNIQUE (source_event_id, channel)                notifications_source_channel_key
--   the worker's due deliveries                             (next_attempt_at) WHERE pending                  notifications_due
--   preferences                                             PRIMARY KEY (account_id)                         notification_prefs_pkey
--   one running request per account and kind                UNIQUE (account_id, kind) WHERE running          privacy_requests_one_running
--   the saga's due requests                                 (next_check_at) WHERE running                    privacy_requests_due
--   GET /v1/privacy/requests, newest first                  (account_id, created_at DESC, id DESC)           privacy_requests_list

-- ---------------------------------------------------------------- actor predicates
--   SYSTEM     the consumers, the notification worker, the saga and the anchor job (set by TenantTx, never a token)
--   operators  SUPPORT / SUPER_ADMIN on the admin pool (the verified admin token's role; audit:read is checked first)
CREATE FUNCTION audit.is_system() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT coalesce(audit.app_actor_role() = 'SYSTEM', false) $$;
CREATE FUNCTION audit.is_operator() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT coalesce(audit.app_actor_role() IN ('SUPPORT', 'SUPER_ADMIN'), false) $$;
REVOKE ALL ON FUNCTION audit.is_system() FROM PUBLIC;
REVOKE ALL ON FUNCTION audit.is_operator() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.is_system(), audit.is_operator() TO audit_app, audit_admin, audit_readonly;

-- ---------------------------------------------------------------- the audit log (append-only hash chain)
-- One row per audited action. hash = SHA-256(prev_hash || canonical row), the canonical row being every column but
-- the two hashes, rendered by audit.chain_hash (format %L keeps NULL and '' apart; jsonb's text form is canonical;
-- occurred_at as exact epoch microseconds). seq is gap-free: audit.append is the only writer and holds the advisory
-- lock and the chain head's row lock until commit, so the chain is one line. Partitioned monthly by occurred_at;
-- no role may UPDATE or DELETE a row (no grant, and a trigger refuses even the owner).
CREATE TABLE audit.audit_log (
  seq          bigint      NOT NULL CHECK (seq > 0),
  occurred_at  timestamptz NOT NULL,
  id           uuid        NOT NULL,
  actor_type   text        NOT NULL CHECK (actor_type IN ('user', 'admin', 'service', 'system')),
  actor_id     text        CHECK (actor_id IS NULL OR length(actor_id) BETWEEN 1 AND 80),
  action       text        NOT NULL CHECK (action ~ '^[a-z][a-z0-9_.-]{1,79}$'),
  target_type  text        CHECK (target_type IS NULL OR target_type ~ '^[a-z][a-z0-9_]{1,39}$'),
  target_id    text        CHECK (target_id IS NULL OR length(target_id) BETWEEN 1 AND 120),
  ip_hash      bytea       CHECK (ip_hash IS NULL OR length(ip_hash) = 32),
  request_id   text        CHECK (request_id IS NULL OR length(request_id) BETWEEN 1 AND 80),
  details      jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(details) = 'object'),
  prev_hash    bytea       NOT NULL CHECK (length(prev_hash) = 32),
  hash         bytea       NOT NULL CHECK (length(hash) = 32),
  PRIMARY KEY (seq, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX audit_log_occurred_brin ON audit.audit_log USING brin (occurred_at);
CREATE INDEX audit_log_actor ON audit.audit_log (actor_id, seq);
CREATE INDEX audit_log_target ON audit.audit_log (target_type, target_id, seq);
CREATE INDEX audit_log_action ON audit.audit_log (action, seq);
ALTER TABLE audit.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_log FORCE  ROW LEVEL SECURITY;
CREATE POLICY audit_log_operator ON audit.audit_log FOR SELECT TO audit_admin USING ((SELECT audit.is_operator()));
CREATE POLICY audit_log_owner ON audit.audit_log FOR ALL TO audit_owner USING (true) WITH CHECK (true);
GRANT SELECT ON audit.audit_log TO audit_admin;
REVOKE UPDATE, DELETE, TRUNCATE ON audit.audit_log FROM PUBLIC, audit_app, audit_admin, audit_relay, audit_readonly;
SELECT audit.create_partitions_ahead('audit_log', 'month', 3, now() - interval '1 month');

CREATE FUNCTION audit.refuse_change() RETURNS trigger
LANGUAGE plpgsql AS
$$ BEGIN RAISE EXCEPTION 'audit_log is append-only' USING ERRCODE = 'insufficient_privilege'; END $$;
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit.audit_log
  FOR EACH ROW EXECUTE FUNCTION audit.refuse_change();

-- The chain head: the last seq and its hash (seq 0 = the genesis hash). One row, updated by audit.append only.
CREATE TABLE audit.chain_head (
  id          smallint    PRIMARY KEY CHECK (id = 1),
  seq         bigint      NOT NULL CHECK (seq >= 0),
  hash        bytea       NOT NULL CHECK (length(hash) = 32),
  occurred_at timestamptz NOT NULL
);
ALTER TABLE audit.chain_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.chain_head FORCE  ROW LEVEL SECURITY;
CREATE POLICY chain_head_operator ON audit.chain_head FOR SELECT TO audit_admin USING ((SELECT audit.is_operator()));
CREATE POLICY chain_head_owner ON audit.chain_head FOR ALL TO audit_owner USING (true) WITH CHECK (true);
GRANT SELECT ON audit.chain_head TO audit_admin;
INSERT INTO audit.chain_head (id, seq, hash, occurred_at)
  VALUES (1, 0, sha256(convert_to('dexter-audit-genesis', 'UTF8')), '2026-01-01T00:00:00Z');

-- The canonical form of a row and its hash. STABLE (format/jsonb output), never volatile.
CREATE FUNCTION audit.chain_hash(p_prev bytea, p_seq bigint, p_occurred_at timestamptz, p_id uuid, p_actor_type text,
    p_actor_id text, p_action text, p_target_type text, p_target_id text, p_ip_hash bytea, p_request_id text,
    p_details jsonb) RETURNS bytea
LANGUAGE sql STABLE PARALLEL SAFE SET search_path = pg_catalog AS
$$
  SELECT sha256(p_prev || convert_to(format('%s|%s|%s|%L|%L|%L|%L|%L|%L|%L|%L', p_seq,
           (extract(epoch FROM p_occurred_at) * 1000000)::bigint, p_id, p_actor_type, p_actor_id, p_action,
           p_target_type, p_target_id, encode(p_ip_hash, 'hex'), p_request_id, p_details::text), 'UTF8'))
$$;
GRANT EXECUTE ON FUNCTION audit.chain_hash(bytea, bigint, timestamptz, uuid, text, text, text, text, text, bytea,
  text, jsonb) TO audit_admin, audit_app;

-- The single writer. Callers: the app pool (consumers, user privacy requests, the saga) and the admin pool (an
-- operator's verification is itself audited). occurred_at never goes backwards along seq.
CREATE FUNCTION audit.append(p_actor_type text, p_actor_id text, p_action text, p_target_type text,
    p_target_id text, p_ip_hash bytea, p_request_id text, p_details jsonb) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
  v_seq  bigint;
  v_prev bytea;
  v_last timestamptz;
  v_at   timestamptz;
  v_id   uuid := gen_random_uuid();
  v_hash bytea;
  v_details jsonb := coalesce(p_details, '{}'::jsonb);
BEGIN
  PERFORM pg_advisory_xact_lock(hashtext('audit.chain'));
  SELECT seq, hash, occurred_at INTO v_seq, v_prev, v_last FROM audit.chain_head WHERE id = 1 FOR UPDATE;
  v_seq := v_seq + 1;
  v_at := greatest(clock_timestamp(), v_last);
  v_hash := audit.chain_hash(v_prev, v_seq, v_at, v_id, p_actor_type, p_actor_id, p_action, p_target_type,
                             p_target_id, p_ip_hash, p_request_id, v_details);
  INSERT INTO audit.audit_log (seq, occurred_at, id, actor_type, actor_id, action, target_type, target_id, ip_hash,
                               request_id, details, prev_hash, hash)
  VALUES (v_seq, v_at, v_id, p_actor_type, p_actor_id, p_action, p_target_type, p_target_id, p_ip_hash, p_request_id,
          v_details, v_prev, v_hash);
  UPDATE audit.chain_head SET seq = v_seq, hash = v_hash, occurred_at = v_at WHERE id = 1;
  RETURN v_seq;
END $$;
REVOKE ALL ON FUNCTION audit.append(text, text, text, text, text, bytea, text, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.append(text, text, text, text, text, bytea, text, jsonb) TO audit_app, audit_admin;

-- Anchors: the chain head at a moment, also exported to the object store (anchors/<seq>.json). Verification checks
-- each anchor's hash against the row at its seq, so rewriting the chain after an anchor is caught even when every
-- row's own hash was recomputed. The job writes one per interval (a day in production; minutes in dev).
CREATE TABLE audit.audit_anchors (
  seq         bigint      PRIMARY KEY CHECK (seq >= 0),
  hash        bytea       NOT NULL CHECK (length(hash) = 32),
  anchored_at timestamptz NOT NULL DEFAULT now(),
  object_key  text        CHECK (object_key IS NULL OR object_key ~ '^anchors/[0-9]{12}\.json$')
);
ALTER TABLE audit.audit_anchors ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_anchors FORCE  ROW LEVEL SECURITY;
CREATE POLICY audit_anchors_system ON audit.audit_anchors FOR ALL TO audit_app
  USING ((SELECT audit.is_system())) WITH CHECK ((SELECT audit.is_system()));
CREATE POLICY audit_anchors_operator ON audit.audit_anchors FOR SELECT TO audit_admin
  USING ((SELECT audit.is_operator()));
CREATE POLICY audit_anchors_owner ON audit.audit_anchors FOR ALL TO audit_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON audit.audit_anchors TO audit_app;
GRANT SELECT ON audit.audit_anchors TO audit_admin;

-- The anchor job reads the head (SYSTEM on the app pool).
CREATE FUNCTION audit.chain_head_now() RETURNS TABLE (seq bigint, hash bytea)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$ SELECT h.seq, h.hash FROM audit.chain_head h WHERE h.id = 1 $$;
REVOKE ALL ON FUNCTION audit.chain_head_now() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.chain_head_now() TO audit_app, audit_admin;

-- ---------------------------------------------------------------- activity (partitioned daily, 90 days)
-- One row per gateway activity event (sampled at the edge). id = the event id, so a redelivery inserts nothing
-- (ON CONFLICT DO NOTHING) and adds nothing to the hourly aggregate written in the same statement's transaction.
CREATE TABLE audit.activity_events (
  id            uuid        NOT NULL,
  occurred_at   timestamptz NOT NULL,
  account_id    uuid,
  request_id    text        NOT NULL CHECK (length(request_id) BETWEEN 1 AND 80),
  service       text        NOT NULL CHECK (service ~ '^[a-z][a-z0-9-]{1,31}$'),
  route_pattern text        NOT NULL CHECK (length(route_pattern) BETWEEN 1 AND 200),
  route_class   text        NOT NULL CHECK (route_class ~ '^[a-z]{1,16}$'),
  method        text        NOT NULL CHECK (method ~ '^[A-Z]{3,7}$'),
  status        integer     NOT NULL CHECK (status BETWEEN 100 AND 599),
  duration_ms   integer     NOT NULL CHECK (duration_ms >= 0),
  PRIMARY KEY (occurred_at, id)
) PARTITION BY RANGE (occurred_at);
ALTER TABLE audit.activity_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.activity_events FORCE  ROW LEVEL SECURITY;
CREATE POLICY activity_events_system ON audit.activity_events FOR ALL TO audit_app
  USING ((SELECT audit.is_system())) WITH CHECK ((SELECT audit.is_system()));
CREATE POLICY activity_events_own ON audit.activity_events FOR SELECT TO audit_app
  USING (account_id = (SELECT audit.app_account()));
CREATE POLICY activity_events_owner ON audit.activity_events FOR ALL TO audit_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON audit.activity_events TO audit_app;
SELECT audit.create_partitions_ahead('activity_events', 'day', 3, now() - interval '1 day');

CREATE TABLE audit.activity_hourly (
  hour          timestamptz NOT NULL CHECK (date_trunc('hour', hour) = hour),
  service       text        NOT NULL CHECK (service ~ '^[a-z][a-z0-9-]{1,31}$'),
  route_class   text        NOT NULL CHECK (route_class ~ '^[a-z]{1,16}$'),
  requests      bigint      NOT NULL DEFAULT 0 CHECK (requests >= 0),
  client_errors bigint      NOT NULL DEFAULT 0 CHECK (client_errors >= 0),
  server_errors bigint      NOT NULL DEFAULT 0 CHECK (server_errors >= 0),
  total_ms      bigint      NOT NULL DEFAULT 0 CHECK (total_ms >= 0),
  max_ms        integer     NOT NULL DEFAULT 0 CHECK (max_ms >= 0),
  PRIMARY KEY (hour, service, route_class)
);
ALTER TABLE audit.activity_hourly ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.activity_hourly FORCE  ROW LEVEL SECURITY;
CREATE POLICY activity_hourly_system ON audit.activity_hourly FOR ALL TO audit_app
  USING ((SELECT audit.is_system())) WITH CHECK ((SELECT audit.is_system()));
CREATE POLICY activity_hourly_operator ON audit.activity_hourly FOR SELECT TO audit_admin
  USING ((SELECT audit.is_operator()));
CREATE POLICY activity_hourly_owner ON audit.activity_hourly FOR ALL TO audit_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON audit.activity_hourly TO audit_app;
GRANT SELECT ON audit.activity_hourly TO audit_admin;

-- ---------------------------------------------------------------- notifications
-- One row per (event, channel). In-app rows are delivered on insert (status sent) and are the user's inbox;
-- email / push rows are pending until the worker delivers them, retried with exponential backoff
-- (next_attempt_at) and failed after the last attempt.
CREATE TABLE audit.notifications (
  id              uuid        PRIMARY KEY,
  account_id      uuid        NOT NULL,
  channel         text        NOT NULL CHECK (channel IN ('email', 'push', 'inapp')),
  category        text        NOT NULL CHECK (category IN ('security', 'budget', 'billing', 'commerce', 'privacy')),
  template        text        NOT NULL CHECK (template ~ '^[a-z][a-z0-9_.-]{1,63}$'),
  payload         jsonb       NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  status          text        NOT NULL CHECK (status IN ('pending', 'sent', 'failed')),
  attempts        integer     NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 100),
  next_attempt_at timestamptz,
  last_error      text        CHECK (last_error IS NULL OR length(last_error) <= 300),
  source_event_id uuid        NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  sent_at         timestamptz,
  read_at         timestamptz,
  version         integer     NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT notifications_source_channel_key UNIQUE (source_event_id, channel),
  CONSTRAINT notifications_due_shape CHECK ((status = 'pending') = (next_attempt_at IS NOT NULL)),
  CONSTRAINT notifications_read_inapp CHECK (read_at IS NULL OR channel = 'inapp')
);
CREATE INDEX notifications_inbox ON audit.notifications (account_id, created_at DESC, id DESC)
  WHERE channel = 'inapp';
CREATE INDEX notifications_unread ON audit.notifications (account_id, created_at DESC, id DESC)
  WHERE channel = 'inapp' AND read_at IS NULL;
CREATE INDEX notifications_due ON audit.notifications (next_attempt_at) WHERE status = 'pending';
ALTER TABLE audit.notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.notifications FORCE  ROW LEVEL SECURITY;
CREATE POLICY notifications_own ON audit.notifications FOR ALL TO audit_app
  USING (account_id = (SELECT audit.app_account())) WITH CHECK (account_id = (SELECT audit.app_account()));
CREATE POLICY notifications_system ON audit.notifications FOR ALL TO audit_app
  USING ((SELECT audit.is_system())) WITH CHECK ((SELECT audit.is_system()));
CREATE POLICY notifications_owner ON audit.notifications FOR ALL TO audit_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON audit.notifications TO audit_app;

-- Channel switches and muted categories; no row = the defaults (every channel on, nothing muted). Security
-- notifications cannot be muted and always go in-app and by email.
CREATE TABLE audit.notification_prefs (
  account_id  uuid        PRIMARY KEY,
  email       boolean     NOT NULL DEFAULT true,
  push        boolean     NOT NULL DEFAULT true,
  muted       text[]      NOT NULL DEFAULT '{}'
              CHECK (muted <@ ARRAY['budget', 'billing', 'commerce', 'privacy']::text[]),
  version     integer     NOT NULL DEFAULT 0 CHECK (version >= 0),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE audit.notification_prefs ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.notification_prefs FORCE  ROW LEVEL SECURITY;
CREATE POLICY notification_prefs_own ON audit.notification_prefs FOR ALL TO audit_app
  USING (account_id = (SELECT audit.app_account())) WITH CHECK (account_id = (SELECT audit.app_account()));
CREATE POLICY notification_prefs_system ON audit.notification_prefs FOR SELECT TO audit_app
  USING ((SELECT audit.is_system()));
CREATE POLICY notification_prefs_owner ON audit.notification_prefs FOR ALL TO audit_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON audit.notification_prefs TO audit_app;

-- ---------------------------------------------------------------- privacy requests (the saga's state)
-- One row per export or delete. `phase` is where the saga is (delete: freeze -> erase -> shred -> local -> done;
-- export: export -> archive -> done); `attempt` counts re-asks of the current phase; `steps` holds every
-- service's answer per phase ({"erase": {"ledger": {"rows": 7, "remaining": 0, "attempt": 1, "at": ...}}}).
-- A completed deletion keeps only a pseudonymous record: account_id NULL, subject_ref = HMAC of the account id.
CREATE TABLE audit.privacy_requests (
  id               uuid        PRIMARY KEY,
  account_id       uuid,
  subject_ref      text        CHECK (subject_ref IS NULL OR subject_ref ~ '^[0-9a-f]{64}$'),
  kind             text        NOT NULL CHECK (kind IN ('export', 'delete')),
  status           text        NOT NULL CHECK (status IN ('running', 'completed', 'failed')),
  phase            text        NOT NULL CHECK (phase IN ('freeze', 'erase', 'shred', 'local', 'export', 'archive',
                                                         'done')),
  attempt          integer     NOT NULL DEFAULT 1 CHECK (attempt BETWEEN 1 AND 100),
  services         text[]      NOT NULL,
  steps            jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(steps) = 'object'),
  phase_started_at timestamptz NOT NULL DEFAULT now(),
  next_check_at    timestamptz,
  archive_key      text        CHECK (archive_key IS NULL OR archive_key ~ '^privacy/[0-9a-f-]{36}/export\.zip$'),
  archive_bytes    bigint      CHECK (archive_bytes IS NULL OR archive_bytes >= 0),
  error            text        CHECK (error IS NULL OR length(error) <= 300),
  created_at       timestamptz NOT NULL DEFAULT now(),
  completed_at     timestamptz,
  version          integer     NOT NULL DEFAULT 0 CHECK (version >= 0),
  CONSTRAINT privacy_requests_subject CHECK (account_id IS NOT NULL OR subject_ref IS NOT NULL),
  CONSTRAINT privacy_requests_due_shape CHECK ((status = 'running') = (next_check_at IS NOT NULL))
);
CREATE UNIQUE INDEX privacy_requests_one_running ON audit.privacy_requests (account_id, kind)
  WHERE status = 'running';
CREATE INDEX privacy_requests_due ON audit.privacy_requests (next_check_at) WHERE status = 'running';
CREATE INDEX privacy_requests_list ON audit.privacy_requests (account_id, created_at DESC, id DESC);
ALTER TABLE audit.privacy_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.privacy_requests FORCE  ROW LEVEL SECURITY;
CREATE POLICY privacy_requests_own ON audit.privacy_requests FOR SELECT TO audit_app
  USING (account_id = (SELECT audit.app_account()));
CREATE POLICY privacy_requests_create ON audit.privacy_requests FOR INSERT TO audit_app
  WITH CHECK (account_id = (SELECT audit.app_account()) AND status = 'running');
CREATE POLICY privacy_requests_system ON audit.privacy_requests FOR ALL TO audit_app
  USING ((SELECT audit.is_system())) WITH CHECK ((SELECT audit.is_system()));
CREATE POLICY privacy_requests_operator ON audit.privacy_requests FOR SELECT TO audit_admin
  USING ((SELECT audit.is_operator()));
CREATE POLICY privacy_requests_owner ON audit.privacy_requests FOR ALL TO audit_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON audit.privacy_requests TO audit_app;
GRANT SELECT ON audit.privacy_requests TO audit_admin;
