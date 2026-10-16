-- MASTER_PLAN 6.4: consumer-side dedupe. One row per (consumer, event) applied.
-- rls:exempt inbox guarded by grants: holds event ids only, no tenant data
CREATE TABLE {{schema}}.inbox (
  consumer    text        NOT NULL,
  event_id    uuid        NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);
CREATE INDEX inbox_received_brin ON {{schema}}.inbox USING brin (received_at);

GRANT SELECT, INSERT ON {{schema}}.inbox TO {{schema}}_app;
GRANT SELECT, DELETE ON {{schema}}.inbox TO {{schema}}_relay;
