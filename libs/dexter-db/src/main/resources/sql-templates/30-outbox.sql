-- MASTER_PLAN 6.3: transactional outbox, range-partitioned by created_at (daily).
-- rls:exempt outbox guarded by grants: app may only INSERT, relay may SELECT/UPDATE (no tenant reads)
CREATE TABLE {{schema}}.outbox (
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

CREATE INDEX outbox_unpublished ON {{schema}}.outbox (created_at, id) WHERE published_at IS NULL;

CREATE OR REPLACE FUNCTION {{schema}}.outbox_notify() RETURNS trigger
LANGUAGE plpgsql AS
$$ BEGIN PERFORM pg_notify('{{schema}}_outbox_new', ''); RETURN NULL; END $$;

CREATE TRIGGER outbox_notify AFTER INSERT ON {{schema}}.outbox
  FOR EACH STATEMENT EXECUTE FUNCTION {{schema}}.outbox_notify();

GRANT INSERT ON {{schema}}.outbox TO {{schema}}_app;
GRANT SELECT, UPDATE ON {{schema}}.outbox TO {{schema}}_relay;

SELECT {{schema}}.create_partitions_ahead('outbox', 'day', 3);
