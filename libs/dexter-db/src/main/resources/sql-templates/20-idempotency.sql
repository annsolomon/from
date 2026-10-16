-- MASTER_PLAN 5.2: Idempotency-Key store. Not partitioned (keys must be unique across time).
-- recovery_point (P02): Mode B (5.3) stores the last completed atomic phase so a retry resumes there.
CREATE TABLE {{schema}}.idempotency_keys (
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
CREATE INDEX idempotency_keys_created_brin ON {{schema}}.idempotency_keys USING brin (created_at);

ALTER TABLE {{schema}}.idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE {{schema}}.idempotency_keys FORCE  ROW LEVEL SECURITY;

CREATE POLICY idempotency_keys_tenant ON {{schema}}.idempotency_keys
  FOR ALL TO {{schema}}_app
  USING      (account_id = (SELECT {{schema}}.app_account()))
  WITH CHECK (account_id = (SELECT {{schema}}.app_account()));

-- Purge job (relay role): may only see and delete expired keys.
CREATE POLICY idempotency_keys_purge_read ON {{schema}}.idempotency_keys
  FOR SELECT TO {{schema}}_relay
  USING (created_at < now() - interval '24 hours');
CREATE POLICY idempotency_keys_purge ON {{schema}}.idempotency_keys
  FOR DELETE TO {{schema}}_relay
  USING (created_at < now() - interval '24 hours');

GRANT SELECT, INSERT, UPDATE, DELETE ON {{schema}}.idempotency_keys TO {{schema}}_app;
GRANT SELECT, DELETE ON {{schema}}.idempotency_keys TO {{schema}}_relay;
