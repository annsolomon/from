-- P12a Portfolio B (MASTER_PLAN P12): statement uploads. A user uploads a statement PDF for one of their cards
-- (presigned PUT into a quarantine key), submits it (scan: size, PDF magic, SHA-256; clean copy; event to the
-- ai-parser), the parser's result is staged here (parsed_statements + parsed_lines, PANs already redacted), the user
-- reviews it and confirms: lines become card_transactions (source 'statement'), idempotent by a per-line reference.
-- Runs as portfolio_owner after V202610221001__portfolio_core.
--
-- Query -> index (BUILD_README §4, P12)
--   GET /v1/statements/uploads (newest first)          (account_id, created_at DESC, id DESC)                 statement_uploads_list
--   GET /v1/statements/uploads?cardId= (newest first)  (account_id, user_card_id, created_at DESC, id DESC)   statement_uploads_card_list
--   one live upload per (card, file)                   UNIQUE (account_id, user_card_id, sha256) WHERE live    statement_uploads_same_file
--   upload by id / lock                                PRIMARY KEY (id)                                       statement_uploads_pkey
--   parsed header of an upload                         PRIMARY KEY (upload_id)                                parsed_statements_pkey
--   GET /v1/statements/uploads/{id}/lines (line order) PRIMARY KEY (upload_id, line_no)                       parsed_lines_pkey
--   duplicate check per line on parse and confirm      UNIQUE (account_id, client_ref) on card_transactions   card_transactions_client_ref

-- ---------------------------------------------------------------- uploads (the lifecycle)
-- awaiting_upload -> submitted (scan passed, event sent) -> parsed | failed -> confirmed | discarded.
-- quarantine_key: where the browser PUTs; object_key: the scanned copy the parser reads (deleted after a decision).
CREATE TABLE portfolio.statement_uploads (
  id              uuid        PRIMARY KEY,
  account_id      uuid        NOT NULL,
  user_card_id    uuid        NOT NULL REFERENCES portfolio.user_cards (id),
  file_name       text        NOT NULL CHECK (length(file_name) BETWEEN 1 AND 200),
  size_bytes      bigint      CHECK (size_bytes IS NULL OR size_bytes BETWEEN 1 AND 104857600),
  quarantine_key  text        NOT NULL CHECK (length(quarantine_key) BETWEEN 1 AND 300),
  object_key      text        CHECK (object_key IS NULL OR length(object_key) BETWEEN 1 AND 300),
  sha256          bytea       CHECK (sha256 IS NULL OR length(sha256) = 32),
  status          text        NOT NULL DEFAULT 'awaiting_upload'
                  CHECK (status IN ('awaiting_upload', 'submitted', 'parsed', 'failed', 'confirmed', 'discarded')),
  error_code      text        CHECK (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  error_detail    text        CHECK (error_detail IS NULL OR length(error_detail) <= 200),
  imported_count  integer     CHECK (imported_count IS NULL OR imported_count >= 0),
  duplicate_count integer     CHECK (duplicate_count IS NULL OR duplicate_count >= 0),
  excluded_count  integer     CHECK (excluded_count IS NULL OR excluded_count >= 0),
  submitted_at    timestamptz,
  parsed_at       timestamptz,
  decided_at      timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  version         integer     NOT NULL DEFAULT 0,
  CONSTRAINT statement_uploads_scanned CHECK (status IN ('awaiting_upload', 'failed', 'discarded')
                                              OR (sha256 IS NOT NULL AND object_key IS NOT NULL)),
  CONSTRAINT statement_uploads_failure CHECK ((status = 'failed') = (error_code IS NOT NULL)),
  CONSTRAINT statement_uploads_confirmed CHECK ((status = 'confirmed') = (imported_count IS NOT NULL))
);
CREATE INDEX statement_uploads_list ON portfolio.statement_uploads (account_id, created_at DESC, id DESC);
CREATE INDEX statement_uploads_card_list ON portfolio.statement_uploads (account_id, user_card_id, created_at DESC,
  id DESC);
-- The same file for the same card is live once (a second upload of it is refused; after a discard or a failure it
-- may be uploaded again).
CREATE UNIQUE INDEX statement_uploads_same_file ON portfolio.statement_uploads (account_id, user_card_id, sha256)
  WHERE status IN ('submitted', 'parsed', 'confirmed');
ALTER TABLE portfolio.statement_uploads ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio.statement_uploads FORCE  ROW LEVEL SECURITY;
CREATE POLICY statement_uploads_tenant ON portfolio.statement_uploads FOR ALL TO portfolio_app
  USING      (account_id = (SELECT portfolio.app_account()))
  WITH CHECK (account_id = (SELECT portfolio.app_account()));
CREATE POLICY statement_uploads_owner ON portfolio.statement_uploads FOR ALL TO portfolio_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON portfolio.statement_uploads TO portfolio_app;

-- ---------------------------------------------------------------- the parser's header (one per parsed upload)
CREATE TABLE portfolio.parsed_statements (
  upload_id       uuid        PRIMARY KEY REFERENCES portfolio.statement_uploads (id),
  account_id      uuid        NOT NULL,
  parser_version  text        NOT NULL CHECK (length(parser_version) BETWEEN 1 AND 40),
  format          text        CHECK (format IS NULL OR length(format) <= 40),
  issuer          text        CHECK (issuer IS NULL OR length(issuer) <= 40),
  last4           char(4)     CHECK (last4 IS NULL OR last4 ~ '^[0-9]{4}$'),
  period_start    date,
  period_end      date,
  due_date        date,
  total_due_paise bigint      CHECK (total_due_paise IS NULL OR total_due_paise >= 0),
  min_due_paise   bigint      CHECK (min_due_paise IS NULL OR min_due_paise >= 0),
  redactions      integer     NOT NULL DEFAULT 0 CHECK (redactions >= 0),
  line_count      integer     NOT NULL CHECK (line_count BETWEEN 1 AND 2000),
  debits_paise    bigint      NOT NULL CHECK (debits_paise >= 0),
  credits_paise   bigint      NOT NULL CHECK (credits_paise >= 0),
  duplicate_count integer     NOT NULL DEFAULT 0 CHECK (duplicate_count >= 0),
  parsed_at       timestamptz NOT NULL,
  CONSTRAINT parsed_statements_period CHECK (period_start IS NULL OR period_end IS NULL OR period_end >= period_start)
);
ALTER TABLE portfolio.parsed_statements ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio.parsed_statements FORCE  ROW LEVEL SECURITY;
CREATE POLICY parsed_statements_tenant ON portfolio.parsed_statements FOR ALL TO portfolio_app
  USING      (account_id = (SELECT portfolio.app_account()))
  WITH CHECK (account_id = (SELECT portfolio.app_account()));
CREATE POLICY parsed_statements_owner ON portfolio.parsed_statements FOR ALL TO portfolio_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON portfolio.parsed_statements TO portfolio_app;

-- ---------------------------------------------------------------- the parsed lines (staged until the decision)
-- client_ref: the reference the line gets as a card transaction ('stmt:' + hash of card, day, amount, direction,
-- description and occurrence), so a statement confirmed twice, or two files with the same lines, import once.
CREATE TABLE portfolio.parsed_lines (
  upload_id    uuid    NOT NULL REFERENCES portfolio.statement_uploads (id),
  line_no      integer NOT NULL CHECK (line_no BETWEEN 1 AND 2000),
  account_id   uuid    NOT NULL,
  posted_on    date    NOT NULL,
  description  text    NOT NULL CHECK (length(description) BETWEEN 1 AND 200),
  amount_paise bigint  NOT NULL CHECK (amount_paise > 0 AND amount_paise <= 1000000000000),
  direction    text    NOT NULL CHECK (direction IN ('debit', 'credit')),
  kind         text    NOT NULL
               CHECK (kind IN ('purchase', 'refund', 'payment', 'fee', 'interest', 'cashback', 'emi')),
  client_ref   text    NOT NULL CHECK (length(client_ref) BETWEEN 1 AND 100),
  status       text    NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'imported', 'duplicate', 'excluded')),
  card_txn_id  uuid    REFERENCES portfolio.card_transactions (id),
  PRIMARY KEY (upload_id, line_no),
  CONSTRAINT parsed_lines_kind_direction CHECK (
    (kind IN ('purchase', 'fee', 'interest', 'emi') AND direction = 'debit')
    OR (kind IN ('refund', 'payment', 'cashback') AND direction = 'credit')),
  CONSTRAINT parsed_lines_imported CHECK ((status = 'imported') = (card_txn_id IS NOT NULL))
);
ALTER TABLE portfolio.parsed_lines ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio.parsed_lines FORCE  ROW LEVEL SECURITY;
CREATE POLICY parsed_lines_tenant ON portfolio.parsed_lines FOR ALL TO portfolio_app
  USING      (account_id = (SELECT portfolio.app_account()))
  WITH CHECK (account_id = (SELECT portfolio.app_account()));
CREATE POLICY parsed_lines_owner ON portfolio.parsed_lines FOR ALL TO portfolio_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON portfolio.parsed_lines TO portfolio_app;
