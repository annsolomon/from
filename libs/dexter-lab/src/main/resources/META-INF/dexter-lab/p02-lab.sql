-- DEV-ONLY P02 lab tables (dexter-lab, A-23). Applied once as lab_owner after the standard templates.
-- Query -> index:
--   notes list   (account_id, created_at DESC, id DESC) -> notes_list
--   note detail  (account_id, id)                       -> notes_pkey
--   titles list  (account_id, note_id)                  -> note_titles_pkey
--   outbox viewer (message_key, created_at DESC)        -> outbox_lab_key (dev viewer only)
CREATE TABLE lab.notes (
  account_id   uuid        NOT NULL,
  id           uuid        NOT NULL,
  title        text        NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  amount_paise bigint      NOT NULL CHECK (amount_paise >= 0),
  created_at   timestamptz NOT NULL,
  version      bigint      NOT NULL DEFAULT 1,
  PRIMARY KEY (account_id, id)
);
CREATE INDEX notes_list ON lab.notes (account_id, created_at DESC, id DESC);
ALTER TABLE lab.notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE lab.notes FORCE  ROW LEVEL SECURITY;
CREATE POLICY notes_tenant ON lab.notes FOR ALL TO lab_app
  USING      (account_id = (SELECT lab.app_account()))
  WITH CHECK (account_id = (SELECT lab.app_account()));
GRANT SELECT, INSERT, UPDATE ON lab.notes TO lab_app;

CREATE TABLE lab.note_titles (
  account_id        uuid   NOT NULL,
  note_id           uuid   NOT NULL,
  title             text   NOT NULL,
  projected_version bigint NOT NULL,
  PRIMARY KEY (account_id, note_id)
);
ALTER TABLE lab.note_titles ENABLE ROW LEVEL SECURITY;
ALTER TABLE lab.note_titles FORCE  ROW LEVEL SECURITY;
CREATE POLICY note_titles_tenant ON lab.note_titles FOR ALL TO lab_app
  USING      (account_id = (SELECT lab.app_account()))
  WITH CHECK (account_id = (SELECT lab.app_account()));
GRANT SELECT, INSERT, UPDATE ON lab.note_titles TO lab_app;

CREATE TABLE lab.note_stats (
  account_id   uuid   NOT NULL PRIMARY KEY,
  notes        bigint NOT NULL,
  amount_paise bigint NOT NULL
);
ALTER TABLE lab.note_stats ENABLE ROW LEVEL SECURITY;
ALTER TABLE lab.note_stats FORCE  ROW LEVEL SECURITY;
CREATE POLICY note_stats_tenant ON lab.note_stats FOR ALL TO lab_app
  USING      (account_id = (SELECT lab.app_account()))
  WITH CHECK (account_id = (SELECT lab.app_account()));
GRANT SELECT, INSERT, UPDATE ON lab.note_stats TO lab_app;

-- the trail's outbox viewer filters by account (message_key); partitioned index, created on every partition
CREATE INDEX outbox_lab_key ON lab.outbox (message_key, created_at DESC);
