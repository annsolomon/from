-- P02 demo module (dexter-test): the template every service copies. Applied as demo_owner after the
-- standard templates (00..40). Query -> index:
--   list (account_id, created_at DESC, id DESC)  -> notes_list
--   get/rename by (account_id, id)               -> notes_pkey
CREATE TABLE demo.notes (
  account_id   uuid        NOT NULL,
  id           uuid        NOT NULL,
  title        text        NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  kind         text        NOT NULL CHECK (kind IN ('note', 'memo')),
  amount_paise bigint      NOT NULL CHECK (amount_paise >= 0),
  created_at   timestamptz NOT NULL,
  version      bigint      NOT NULL DEFAULT 1,
  PRIMARY KEY (account_id, id)
);
CREATE INDEX notes_list ON demo.notes (account_id, created_at DESC, id DESC);
ALTER TABLE demo.notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE demo.notes FORCE  ROW LEVEL SECURITY;
CREATE POLICY notes_tenant ON demo.notes FOR ALL TO demo_app
  USING      (account_id = (SELECT demo.app_account()))
  WITH CHECK (account_id = (SELECT demo.app_account()));
GRANT SELECT, INSERT, UPDATE ON demo.notes TO demo_app;

-- Consumer-side projections (in a real system these live in the consuming service).
CREATE TABLE demo.note_stats (
  account_id   uuid   NOT NULL PRIMARY KEY,
  notes        bigint NOT NULL,
  amount_paise bigint NOT NULL
);
ALTER TABLE demo.note_stats ENABLE ROW LEVEL SECURITY;
ALTER TABLE demo.note_stats FORCE  ROW LEVEL SECURITY;
CREATE POLICY note_stats_tenant ON demo.note_stats FOR ALL TO demo_app
  USING      (account_id = (SELECT demo.app_account()))
  WITH CHECK (account_id = (SELECT demo.app_account()));
GRANT SELECT, INSERT, UPDATE ON demo.note_stats TO demo_app;

CREATE TABLE demo.note_titles (
  account_id        uuid   NOT NULL,
  note_id           uuid   NOT NULL,
  title             text   NOT NULL,
  projected_version bigint NOT NULL,
  PRIMARY KEY (account_id, note_id)
);
ALTER TABLE demo.note_titles ENABLE ROW LEVEL SECURITY;
ALTER TABLE demo.note_titles FORCE  ROW LEVEL SECURITY;
CREATE POLICY note_titles_tenant ON demo.note_titles FOR ALL TO demo_app
  USING      (account_id = (SELECT demo.app_account()))
  WITH CHECK (account_id = (SELECT demo.app_account()));
GRANT SELECT, INSERT, UPDATE ON demo.note_titles TO demo_app;
