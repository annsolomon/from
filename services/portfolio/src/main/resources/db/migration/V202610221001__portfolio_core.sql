-- P11 Portfolio A (MASTER_PLAN P11): user cards, statements, card transactions, expense links (auto-match from
-- ledger.expense.* events), EMI plans + schedules, the reward points ledger, monthly card savings, and two
-- projections (catalog cards from catalog.card.published/retired, card expenses from ledger.expense.*).
-- Runs as portfolio_owner after V202610221000__platform.
--
-- Query -> index (BUILD_README §4, P11)
--   GET /v1/cards (keyset, newest first)            (account_id, created_at DESC, id DESC) WHERE status <> removed  user_cards_list
--   one live card per (product, last4)              UNIQUE (account_id, card_product_id, last4) WHERE live           user_cards_key
--   GET /v1/cards/{id}/statements (newest first)    (account_id, user_card_id, period_start DESC, id DESC)          statements_list
--   statement of a posting day / closer job         UNIQUE (user_card_id, period_start); (period_end) WHERE open     statements_card_period_key, statements_open
--   GET /v1/cards/{id}/transactions (newest first)  (account_id, user_card_id, posted_at DESC, id DESC)             card_transactions_list
--   create with clientRef (dedupe)                  UNIQUE (account_id, client_ref) WHERE client_ref IS NOT NULL     card_transactions_client_ref
--   auto-match candidates (card, amount, ±2 days)   (account_id, user_card_id, amount_paise, posted_at) WHERE open   card_transactions_match
--                                                   (account_id, user_card_id, amount_paise, day) WHERE live         ledger_expenses_proj_match
--   GET /v1/links/suggestions (newest first)        (account_id, created_at DESC, id DESC) WHERE suggested           expense_links_suggested
--   one confirmed link per transaction / expense    UNIQUE (card_txn_id) / (account_id, expense_id) WHERE confirmed  expense_links_txn_confirmed, ..._expense_confirmed
--   GET /v1/emi-plans (newest first)                (account_id, created_at DESC, id DESC)                           emi_plans_list
--   GET /v1/emi-plans/{id}/schedule                 PRIMARY KEY (plan_id, installment_no)                            emi_schedule_pkey
--   GET /v1/rewards/ledger (newest first)           (account_id, created_at DESC, id DESC)                           reward_points_ledger_list
--   earn once per transaction                       UNIQUE (account_id, source_ref) WHERE source_ref IS NOT NULL     reward_points_ledger_source
--   /v1/rewards/balance (per card, FIFO expiry)     (account_id, user_card_id, created_at)                           reward_points_ledger_card
--   /v1/portfolio/report (month / year)             PRIMARY KEY (account_id, user_card_id, period)                   card_savings_summary_pkey
--   card product lookups                            PRIMARY KEY (card_product_id)                                    catalog_products_proj_pkey

-- ---------------------------------------------------------------- shared predicate: the projector actor
-- Global projections (catalog cards) are written only by the event consumer, whose transaction carries the actor
-- role PROJECTOR (a role no token carries); every tenant reads them.
CREATE FUNCTION portfolio.is_projector() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT coalesce(portfolio.app_actor_role() = 'PROJECTOR', false) $$;
REVOKE ALL ON FUNCTION portfolio.is_projector() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION portfolio.is_projector() TO portfolio_app, portfolio_readonly;

-- ---------------------------------------------------------------- catalog projection (global)
-- From the compacted dexter.catalog.card.v1 (latest event per card); projected_version = the event's aggregate
-- version, so a stale or replayed event never overwrites a newer one (ProjectionVersionGuard). The seed writes the
-- seeded catalog's rows with projected_version 0 (as if consumed from the topic).
CREATE TABLE portfolio.catalog_products_proj (
  card_product_id   uuid        PRIMARY KEY,
  slug              text        NOT NULL,
  name              text        NOT NULL,
  issuer_code       text        NOT NULL,
  network           text        NOT NULL,
  tier              text        NOT NULL,
  annual_fee_paise  bigint      NOT NULL DEFAULT 0,
  status            text        NOT NULL CHECK (status IN ('published', 'retired')),
  current_version   integer     NOT NULL CHECK (current_version >= 1),
  snapshot          jsonb       NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
  projected_version bigint      NOT NULL CHECK (projected_version >= 0),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE portfolio.catalog_products_proj ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio.catalog_products_proj FORCE  ROW LEVEL SECURITY;
CREATE POLICY catalog_products_proj_read ON portfolio.catalog_products_proj FOR SELECT TO portfolio_app
  USING ((SELECT portfolio.app_account()) IS NOT NULL);
CREATE POLICY catalog_products_proj_insert ON portfolio.catalog_products_proj FOR INSERT TO portfolio_app
  WITH CHECK ((SELECT portfolio.is_projector()));
CREATE POLICY catalog_products_proj_update ON portfolio.catalog_products_proj FOR UPDATE TO portfolio_app
  USING ((SELECT portfolio.is_projector())) WITH CHECK ((SELECT portfolio.is_projector()));
CREATE POLICY catalog_products_proj_owner ON portfolio.catalog_products_proj FOR ALL TO portfolio_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON portfolio.catalog_products_proj TO portfolio_app;

-- ---------------------------------------------------------------- user cards
-- statement_day 1-28, or 31 = the last day of the month. Removing a card is soft (status removed): its statements,
-- transactions and points stay; the same product + last4 can be added again.
CREATE TABLE portfolio.user_cards (
  id                 uuid        PRIMARY KEY,
  account_id         uuid        NOT NULL,
  card_product_id    uuid        NOT NULL,
  nickname           text        NOT NULL CHECK (length(nickname) BETWEEN 1 AND 60),
  last4              char(4)     NOT NULL CHECK (last4 ~ '^[0-9]{4}$'),
  network            text        NOT NULL CHECK (network IN ('visa', 'mastercard', 'rupay', 'amex', 'diners')),
  statement_day      smallint    NOT NULL CHECK (statement_day BETWEEN 1 AND 28 OR statement_day = 31),
  due_offset_days    smallint    NOT NULL DEFAULT 20 CHECK (due_offset_days BETWEEN 10 AND 30),
  credit_limit_paise bigint      CHECK (credit_limit_paise IS NULL OR credit_limit_paise > 0),
  opened_on          date,
  status             text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'closed', 'removed')),
  created_at         timestamptz NOT NULL DEFAULT now(),
  updated_at         timestamptz NOT NULL DEFAULT now(),
  removed_at         timestamptz,
  version            integer     NOT NULL DEFAULT 0,
  CONSTRAINT user_cards_removed_shape CHECK ((status = 'removed') = (removed_at IS NOT NULL))
);
CREATE UNIQUE INDEX user_cards_key ON portfolio.user_cards (account_id, card_product_id, last4)
  WHERE status <> 'removed';
CREATE INDEX user_cards_list ON portfolio.user_cards (account_id, created_at DESC, id DESC) WHERE status <> 'removed';
ALTER TABLE portfolio.user_cards ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio.user_cards FORCE  ROW LEVEL SECURITY;
CREATE POLICY user_cards_tenant ON portfolio.user_cards FOR ALL TO portfolio_app
  USING      (account_id = (SELECT portfolio.app_account()))
  WITH CHECK (account_id = (SELECT portfolio.app_account()));
CREATE POLICY user_cards_owner ON portfolio.user_cards FOR ALL TO portfolio_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON portfolio.user_cards TO portfolio_app;

-- ---------------------------------------------------------------- statements (one per card and cycle)
-- Recording a transaction finds or opens the statement of its cycle; the closer job turns ended cycles into
-- 'generated' (minimum due set). paid / partially_paid / overdue arrive with payments (P12).
CREATE TABLE portfolio.statements (
  id              uuid        PRIMARY KEY,
  account_id      uuid        NOT NULL,
  user_card_id    uuid        NOT NULL REFERENCES portfolio.user_cards (id),
  period_start    date        NOT NULL,
  period_end      date        NOT NULL,
  due_date        date        NOT NULL,
  debits_paise    bigint      NOT NULL DEFAULT 0 CHECK (debits_paise >= 0),
  credits_paise   bigint      NOT NULL DEFAULT 0 CHECK (credits_paise >= 0),
  txn_count       integer     NOT NULL DEFAULT 0 CHECK (txn_count >= 0),
  total_due_paise bigint      NOT NULL DEFAULT 0,
  min_due_paise   bigint      NOT NULL DEFAULT 0 CHECK (min_due_paise >= 0),
  status          text        NOT NULL DEFAULT 'open'
                  CHECK (status IN ('open', 'generated', 'paid', 'partially_paid', 'overdue')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  version         integer     NOT NULL DEFAULT 0,
  CONSTRAINT statements_card_period_key UNIQUE (user_card_id, period_start),
  CONSTRAINT statements_period_shape CHECK (period_end >= period_start AND due_date > period_end
                                            AND period_end - period_start <= 31)
);
CREATE INDEX statements_list ON portfolio.statements (account_id, user_card_id, period_start DESC, id DESC);
CREATE INDEX statements_open ON portfolio.statements (period_end) WHERE status = 'open';
ALTER TABLE portfolio.statements ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio.statements FORCE  ROW LEVEL SECURITY;
CREATE POLICY statements_tenant ON portfolio.statements FOR ALL TO portfolio_app
  USING      (account_id = (SELECT portfolio.app_account()))
  WITH CHECK (account_id = (SELECT portfolio.app_account()));
CREATE POLICY statements_owner ON portfolio.statements FOR ALL TO portfolio_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON portfolio.statements TO portfolio_app;

-- ---------------------------------------------------------------- card transactions
-- kind fixes the direction: purchases, fees, interest and EMI instalments are debits; refunds, payments and
-- cashback credits are credits.
CREATE TABLE portfolio.card_transactions (
  id            uuid         PRIMARY KEY,
  account_id    uuid         NOT NULL,
  user_card_id  uuid         NOT NULL REFERENCES portfolio.user_cards (id),
  statement_id  uuid         REFERENCES portfolio.statements (id),
  posted_at     timestamptz  NOT NULL,
  amount_paise  bigint       NOT NULL CHECK (amount_paise > 0 AND amount_paise <= 1000000000000),
  direction     text         NOT NULL CHECK (direction IN ('debit', 'credit')),
  kind          text         NOT NULL DEFAULT 'purchase'
                CHECK (kind IN ('purchase', 'refund', 'payment', 'fee', 'interest', 'cashback', 'emi')),
  description   text         NOT NULL CHECK (length(description) BETWEEN 1 AND 200),
  merchant_id   uuid,
  category_path public.ltree,
  expense_id    uuid,
  client_ref    text         CHECK (client_ref IS NULL OR length(client_ref) BETWEEN 1 AND 100),
  source        text         NOT NULL DEFAULT 'manual' CHECK (source IN ('manual', 'statement', 'import', 'api')),
  created_at    timestamptz  NOT NULL DEFAULT now(),
  version       integer      NOT NULL DEFAULT 0,
  CONSTRAINT card_transactions_kind_direction CHECK (
    (kind IN ('purchase', 'fee', 'interest', 'emi') AND direction = 'debit')
    OR (kind IN ('refund', 'payment', 'cashback') AND direction = 'credit'))
);
CREATE INDEX card_transactions_list ON portfolio.card_transactions (account_id, user_card_id, posted_at DESC, id DESC);
CREATE UNIQUE INDEX card_transactions_client_ref ON portfolio.card_transactions (account_id, client_ref)
  WHERE client_ref IS NOT NULL;
CREATE INDEX card_transactions_match ON portfolio.card_transactions (account_id, user_card_id, amount_paise, posted_at)
  WHERE expense_id IS NULL AND direction = 'debit';
-- A card belongs to one account: without this the planner multiplies the two equality selectivities, expects a
-- handful of rows per card and sorts instead of walking card_transactions_list.
CREATE STATISTICS portfolio.card_transactions_card (dependencies, ndistinct) ON account_id, user_card_id
  FROM portfolio.card_transactions;
ALTER TABLE portfolio.card_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio.card_transactions FORCE  ROW LEVEL SECURITY;
CREATE POLICY card_transactions_tenant ON portfolio.card_transactions FOR ALL TO portfolio_app
  USING      (account_id = (SELECT portfolio.app_account()))
  WITH CHECK (account_id = (SELECT portfolio.app_account()));
CREATE POLICY card_transactions_owner ON portfolio.card_transactions FOR ALL TO portfolio_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON portfolio.card_transactions TO portfolio_app;

-- ---------------------------------------------------------------- ledger expense projection (per tenant)
-- Card expenses as ledger last described them (dexter.ledger.expense.v1); the auto-matcher's other side.
CREATE TABLE portfolio.ledger_expenses_proj (
  expense_id        uuid        PRIMARY KEY,
  account_id        uuid        NOT NULL,
  user_card_id      uuid,
  amount_paise      bigint      NOT NULL,
  day               date        NOT NULL,
  merchant          text,
  merchant_id       uuid,
  category_path     text,
  deleted           boolean     NOT NULL DEFAULT false,
  projected_version bigint      NOT NULL CHECK (projected_version >= 0),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ledger_expenses_proj_match ON portfolio.ledger_expenses_proj (account_id, user_card_id, amount_paise, day)
  WHERE NOT deleted AND user_card_id IS NOT NULL;
ALTER TABLE portfolio.ledger_expenses_proj ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio.ledger_expenses_proj FORCE  ROW LEVEL SECURITY;
CREATE POLICY ledger_expenses_proj_tenant ON portfolio.ledger_expenses_proj FOR ALL TO portfolio_app
  USING      (account_id = (SELECT portfolio.app_account()))
  WITH CHECK (account_id = (SELECT portfolio.app_account()));
CREATE POLICY ledger_expenses_proj_owner ON portfolio.ledger_expenses_proj FOR ALL TO portfolio_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON portfolio.ledger_expenses_proj TO portfolio_app;

-- ---------------------------------------------------------------- expense <-> card transaction links
CREATE TABLE portfolio.expense_links (
  id          uuid         PRIMARY KEY,
  account_id  uuid         NOT NULL,
  expense_id  uuid         NOT NULL,
  card_txn_id uuid         NOT NULL REFERENCES portfolio.card_transactions (id),
  status      text         NOT NULL CHECK (status IN ('suggested', 'confirmed', 'rejected')),
  score       numeric(4,3) NOT NULL CHECK (score BETWEEN 0 AND 1),
  method      text         NOT NULL DEFAULT 'auto' CHECK (method IN ('auto', 'manual')),
  created_at  timestamptz  NOT NULL DEFAULT now(),
  decided_at  timestamptz,
  version     integer      NOT NULL DEFAULT 0,
  CONSTRAINT expense_links_pair_key UNIQUE (account_id, expense_id, card_txn_id),
  CONSTRAINT expense_links_decision CHECK ((status = 'suggested') = (decided_at IS NULL))
);
CREATE INDEX expense_links_suggested ON portfolio.expense_links (account_id, created_at DESC, id DESC)
  WHERE status = 'suggested';
CREATE UNIQUE INDEX expense_links_txn_confirmed ON portfolio.expense_links (card_txn_id) WHERE status = 'confirmed';
CREATE UNIQUE INDEX expense_links_expense_confirmed ON portfolio.expense_links (account_id, expense_id)
  WHERE status = 'confirmed';
CREATE INDEX expense_links_expense ON portfolio.expense_links (account_id, expense_id);
ALTER TABLE portfolio.expense_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio.expense_links FORCE  ROW LEVEL SECURITY;
CREATE POLICY expense_links_tenant ON portfolio.expense_links FOR ALL TO portfolio_app
  USING      (account_id = (SELECT portfolio.app_account()))
  WITH CHECK (account_id = (SELECT portfolio.app_account()));
CREATE POLICY expense_links_owner ON portfolio.expense_links FOR ALL TO portfolio_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON portfolio.expense_links TO portfolio_app;

-- ---------------------------------------------------------------- EMI plans + schedule
-- Reducing-balance EMI: the schedule is computed once at creation (domain EmiMath) and stored; GST on interest.
CREATE TABLE portfolio.emi_plans (
  id                   uuid        PRIMARY KEY,
  account_id           uuid        NOT NULL,
  user_card_id         uuid        NOT NULL REFERENCES portfolio.user_cards (id),
  card_txn_id          uuid        REFERENCES portfolio.card_transactions (id),
  description          text        NOT NULL CHECK (length(description) BETWEEN 1 AND 120),
  principal_paise      bigint      NOT NULL CHECK (principal_paise BETWEEN 100000 AND 100000000000),
  annual_rate_bps      integer     NOT NULL CHECK (annual_rate_bps BETWEEN 0 AND 6000),
  tenure_months        smallint    NOT NULL CHECK (tenure_months BETWEEN 1 AND 84),
  processing_fee_paise bigint      NOT NULL DEFAULT 0 CHECK (processing_fee_paise >= 0),
  gst_bps              integer     NOT NULL DEFAULT 1800 CHECK (gst_bps BETWEEN 0 AND 2800),
  first_due_date       date        NOT NULL,
  emi_paise            bigint      NOT NULL CHECK (emi_paise > 0),
  total_interest_paise bigint      NOT NULL CHECK (total_interest_paise >= 0),
  total_gst_paise      bigint      NOT NULL CHECK (total_gst_paise >= 0),
  status               text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'closed', 'foreclosed')),
  created_at           timestamptz NOT NULL DEFAULT now(),
  updated_at           timestamptz NOT NULL DEFAULT now(),
  version              integer     NOT NULL DEFAULT 0
);
CREATE INDEX emi_plans_list ON portfolio.emi_plans (account_id, created_at DESC, id DESC);
ALTER TABLE portfolio.emi_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio.emi_plans FORCE  ROW LEVEL SECURITY;
CREATE POLICY emi_plans_tenant ON portfolio.emi_plans FOR ALL TO portfolio_app
  USING      (account_id = (SELECT portfolio.app_account()))
  WITH CHECK (account_id = (SELECT portfolio.app_account()));
CREATE POLICY emi_plans_owner ON portfolio.emi_plans FOR ALL TO portfolio_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON portfolio.emi_plans TO portfolio_app;

CREATE TABLE portfolio.emi_schedule (
  plan_id                 uuid     NOT NULL REFERENCES portfolio.emi_plans (id),
  account_id              uuid     NOT NULL,
  installment_no          smallint NOT NULL CHECK (installment_no >= 1),
  due_date                date     NOT NULL,
  principal_paise         bigint   NOT NULL CHECK (principal_paise >= 0),
  interest_paise          bigint   NOT NULL CHECK (interest_paise >= 0),
  gst_paise               bigint   NOT NULL CHECK (gst_paise >= 0),
  total_paise             bigint   NOT NULL CHECK (total_paise > 0),
  outstanding_after_paise bigint   NOT NULL CHECK (outstanding_after_paise >= 0),
  status                  text     NOT NULL DEFAULT 'due' CHECK (status IN ('due', 'paid', 'cancelled')),
  PRIMARY KEY (plan_id, installment_no)
);
ALTER TABLE portfolio.emi_schedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio.emi_schedule FORCE  ROW LEVEL SECURITY;
CREATE POLICY emi_schedule_tenant ON portfolio.emi_schedule FOR ALL TO portfolio_app
  USING      (account_id = (SELECT portfolio.app_account()))
  WITH CHECK (account_id = (SELECT portfolio.app_account()));
CREATE POLICY emi_schedule_owner ON portfolio.emi_schedule FOR ALL TO portfolio_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON portfolio.emi_schedule TO portfolio_app;

-- ---------------------------------------------------------------- reward points ledger (append-only)
-- Signed points: earn > 0, redeem and expire < 0, adjust either way. value_milli_paise = what the points are worth
-- (points x the card's point value). Earned points expire on expires_on; the balance consumes the oldest lots first.
CREATE TABLE portfolio.reward_points_ledger (
  id                uuid        PRIMARY KEY,
  account_id        uuid        NOT NULL,
  user_card_id      uuid        NOT NULL REFERENCES portfolio.user_cards (id),
  kind              text        NOT NULL CHECK (kind IN ('earn', 'redeem', 'expire', 'adjust')),
  points            bigint      NOT NULL CHECK (points <> 0 AND abs(points) <= 100000000),
  value_milli_paise bigint      NOT NULL DEFAULT 0,
  expires_on        date,
  source_ref        text        CHECK (source_ref IS NULL OR length(source_ref) BETWEEN 1 AND 100),
  card_txn_id       uuid        REFERENCES portfolio.card_transactions (id),
  note              text        CHECK (note IS NULL OR length(note) <= 200),
  created_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT reward_points_sign CHECK (
    (kind = 'earn' AND points > 0) OR (kind IN ('redeem', 'expire') AND points < 0) OR kind = 'adjust'),
  CONSTRAINT reward_points_expiry CHECK (kind = 'earn' OR expires_on IS NULL)
);
CREATE INDEX reward_points_ledger_list ON portfolio.reward_points_ledger (account_id, created_at DESC, id DESC);
CREATE INDEX reward_points_ledger_card ON portfolio.reward_points_ledger (account_id, user_card_id, created_at);
CREATE UNIQUE INDEX reward_points_ledger_source ON portfolio.reward_points_ledger (account_id, source_ref)
  WHERE source_ref IS NOT NULL;
ALTER TABLE portfolio.reward_points_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio.reward_points_ledger FORCE  ROW LEVEL SECURITY;
CREATE POLICY reward_points_ledger_tenant ON portfolio.reward_points_ledger FOR ALL TO portfolio_app
  USING      (account_id = (SELECT portfolio.app_account()))
  WITH CHECK (account_id = (SELECT portfolio.app_account()));
CREATE POLICY reward_points_ledger_owner ON portfolio.reward_points_ledger FOR ALL TO portfolio_owner
  USING (true) WITH CHECK (true);
-- append-only: no UPDATE, no DELETE for the app
GRANT SELECT, INSERT ON portfolio.reward_points_ledger TO portfolio_app;

-- ---------------------------------------------------------------- monthly savings per card
-- Maintained in the transaction that records a card transaction (like ledger's daily totals): spend (purchases net
-- of refunds), rewards value earned (cashback + points value), fees (fees + interest).
CREATE TABLE portfolio.card_savings_summary (
  account_id          uuid    NOT NULL,
  user_card_id        uuid    NOT NULL REFERENCES portfolio.user_cards (id),
  period              date    NOT NULL CHECK (extract(day FROM period) = 1),
  spend_paise         bigint  NOT NULL DEFAULT 0,
  txn_count           integer NOT NULL DEFAULT 0 CHECK (txn_count >= 0),
  rewards_value_paise bigint  NOT NULL DEFAULT 0,
  fees_paise          bigint  NOT NULL DEFAULT 0 CHECK (fees_paise >= 0),
  PRIMARY KEY (account_id, user_card_id, period)
);
ALTER TABLE portfolio.card_savings_summary ENABLE ROW LEVEL SECURITY;
ALTER TABLE portfolio.card_savings_summary FORCE  ROW LEVEL SECURITY;
CREATE POLICY card_savings_summary_tenant ON portfolio.card_savings_summary FOR ALL TO portfolio_app
  USING      (account_id = (SELECT portfolio.app_account()))
  WITH CHECK (account_id = (SELECT portfolio.app_account()));
CREATE POLICY card_savings_summary_owner ON portfolio.card_savings_summary FOR ALL TO portfolio_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON portfolio.card_savings_summary TO portfolio_app;

-- ---------------------------------------------------------------- statement closer (all tenants)
-- The job closes cycles that ended before p_today: status generated, minimum due = 5 % of the total due, at least
-- Rs 100, never more than the total. Runs as the owner (tenant-wide work, no tenant context); returns how many
-- statements it closed. Bounded per call (SKIP LOCKED: several instances share the work).
CREATE FUNCTION portfolio.close_ended_statements(p_today date, p_limit integer) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
DECLARE
  v_count integer;
BEGIN
  IF p_today IS NULL OR p_limit NOT BETWEEN 1 AND 10000 THEN
    RAISE EXCEPTION 'bad closer arguments';
  END IF;
  WITH due AS (
    SELECT id FROM portfolio.statements
     WHERE status = 'open' AND period_end < p_today
     ORDER BY period_end, id
     LIMIT p_limit
     FOR UPDATE SKIP LOCKED
  )
  UPDATE portfolio.statements s
     SET status = 'generated',
         min_due_paise = CASE WHEN s.total_due_paise <= 0 THEN 0
                              ELSE LEAST(s.total_due_paise, GREATEST(10000, ceil(s.total_due_paise * 0.05)::bigint)) END,
         updated_at = now(), version = s.version + 1
    FROM due WHERE s.id = due.id;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count;
END
$$;
REVOKE ALL ON FUNCTION portfolio.close_ended_statements(date, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION portfolio.close_ended_statements(date, integer) TO portfolio_app;
