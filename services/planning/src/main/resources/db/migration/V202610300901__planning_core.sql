-- P13 Planning (MASTER_PLAN P13): budgets with per-period spend kept up to date from ledger expense events,
-- threshold alerts at most once per period, liabilities with their amortization schedule, and what-if scenarios
-- evaluated against a snapshot taken when the scenario was saved. Runs as planning_owner after
-- V202610300900__platform.
--
-- Query -> index (BUILD_README §4, P13)
--   GET /v1/budgets (newest first)                 (account_id, created_at DESC, id DESC)            budgets_list
--   GET /v1/budgets/{id} / lock                    PRIMARY KEY (id)                                  budgets_pkey
--   GET /v1/budgets/status (every active budget)   (account_id) WHERE status = 'active'               budgets_active
--   match an expense to an account's budgets       same partial index, ltree containment as a filter  budgets_active
--   the spend of one budget period                 PRIMARY KEY (account_id, budget_id, period_start)  budget_period_spend_pkey
--   GET /v1/budgets/{id}/history (newest first)     PRIMARY KEY, walked backwards by period_start      budget_period_spend_pkey
--   has this threshold alerted this period?        PRIMARY KEY (account_id, budget_id, period_start, threshold)
--                                                                                                    budget_alerts_sent_pkey
--   GET /v1/liabilities (newest first)             (account_id, created_at DESC, id DESC)            liabilities_list
--   GET /v1/liabilities/{id}/schedule (in order)   PRIMARY KEY (liability_id, installment_no)         liability_schedule_pkey
--   GET /v1/scenarios (newest first)               (account_id, created_at DESC, id DESC)            scenarios_list

-- ---------------------------------------------------------------- budgets
-- category_path NULL means the budget is on total spend. A non-NULL path matches an expense whose own category is
-- that path or below it (ltree containment), so a budget on `food` also counts `food.dining`.
-- alert_thresholds are percentages of the limit; the service stores them sorted and distinct.
CREATE TABLE planning.budgets (
  id               uuid        PRIMARY KEY,
  account_id       uuid        NOT NULL,
  name             text        NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
  period           text        NOT NULL CHECK (period IN ('weekly', 'monthly', 'custom')),
  category_path    public.ltree CHECK (category_path IS NULL OR public.nlevel(category_path) BETWEEN 1 AND 4),
  limit_paise      bigint      NOT NULL CHECK (limit_paise BETWEEN 1 AND 100000000000),
  rollover         boolean     NOT NULL DEFAULT false,
  alert_thresholds smallint[]  NOT NULL DEFAULT '{50,80,100}'
                   CHECK (array_length(alert_thresholds, 1) BETWEEN 1 AND 5
                          AND 1 <= ALL (alert_thresholds) AND 500 >= ALL (alert_thresholds)),
  -- a custom period runs from starts_on to ends_on; weekly and monthly repeat from starts_on
  starts_on        date        NOT NULL,
  ends_on          date        CHECK (ends_on IS NULL OR ends_on >= starts_on),
  status           text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'paused', 'ended')),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  version          integer     NOT NULL DEFAULT 0,
  CONSTRAINT budgets_custom_needs_an_end CHECK (period <> 'custom' OR ends_on IS NOT NULL)
);
CREATE INDEX budgets_list ON planning.budgets (account_id, created_at DESC, id DESC);
CREATE INDEX budgets_active ON planning.budgets (account_id) WHERE status = 'active';

ALTER TABLE planning.budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE planning.budgets FORCE  ROW LEVEL SECURITY;
CREATE POLICY budgets_tenant ON planning.budgets FOR ALL TO planning_app
  USING      (account_id = (SELECT planning.app_account()))
  WITH CHECK (account_id = (SELECT planning.app_account()));
CREATE POLICY budgets_owner ON planning.budgets FOR ALL TO planning_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON planning.budgets TO planning_app;

-- ---------------------------------------------------------------- spend per budget period
-- Maintained by the expense consumer as a delta per matching budget, in the same transaction as its inbox row
-- (ADR-0019): the inbox is what makes it exactly-once, because a delta is not idempotent under replay.
-- projected_version records the ledger expense version last applied to the row.
-- A budget created mid-period starts at zero: only expense events seen after it exists are counted, so a delete of
-- an earlier expense is floored at zero rather than driving the row negative (BUILD_README §7).
CREATE TABLE planning.budget_period_spend (
  account_id        uuid        NOT NULL,
  budget_id         uuid        NOT NULL REFERENCES planning.budgets (id) ON DELETE CASCADE,
  period_start      date        NOT NULL,
  spent_paise       bigint      NOT NULL DEFAULT 0 CHECK (spent_paise >= 0),
  projected_version bigint      NOT NULL DEFAULT 0 CHECK (projected_version >= 0),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, budget_id, period_start)
);

ALTER TABLE planning.budget_period_spend ENABLE ROW LEVEL SECURITY;
ALTER TABLE planning.budget_period_spend FORCE  ROW LEVEL SECURITY;
CREATE POLICY budget_period_spend_tenant ON planning.budget_period_spend FOR ALL TO planning_app
  USING      (account_id = (SELECT planning.app_account()))
  WITH CHECK (account_id = (SELECT planning.app_account()));
CREATE POLICY budget_period_spend_owner ON planning.budget_period_spend FOR ALL TO planning_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON planning.budget_period_spend TO planning_app;

-- ---------------------------------------------------------------- thresholds already alerted
-- One row per (budget, period, threshold): the primary key is what makes an alert fire at most once per period.
CREATE TABLE planning.budget_alerts_sent (
  account_id   uuid        NOT NULL,
  budget_id    uuid        NOT NULL REFERENCES planning.budgets (id) ON DELETE CASCADE,
  period_start date        NOT NULL,
  threshold    smallint    NOT NULL CHECK (threshold BETWEEN 1 AND 500),
  spent_paise  bigint      NOT NULL CHECK (spent_paise >= 0),
  sent_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, budget_id, period_start, threshold)
);

ALTER TABLE planning.budget_alerts_sent ENABLE ROW LEVEL SECURITY;
ALTER TABLE planning.budget_alerts_sent FORCE  ROW LEVEL SECURITY;
CREATE POLICY budget_alerts_sent_tenant ON planning.budget_alerts_sent FOR ALL TO planning_app
  USING      (account_id = (SELECT planning.app_account()))
  WITH CHECK (account_id = (SELECT planning.app_account()));
CREATE POLICY budget_alerts_sent_owner ON planning.budget_alerts_sent FOR ALL TO planning_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON planning.budget_alerts_sent TO planning_app;

-- ---------------------------------------------------------------- liabilities
-- A loan, a card EMI, a BNPL plan or anything else with a monthly instalment. emi_paise is computed by the shared
-- EMI engine (libs/portfolio-calc, A-74) at create time and stored, so a schedule never silently re-rates.
CREATE TABLE planning.liabilities (
  id              uuid        PRIMARY KEY,
  account_id      uuid        NOT NULL,
  kind            text        NOT NULL CHECK (kind IN ('loan', 'card_emi', 'bnpl', 'other')),
  lender          text        NOT NULL CHECK (length(lender) BETWEEN 1 AND 80),
  principal_paise bigint      NOT NULL CHECK (principal_paise BETWEEN 1 AND 100000000000),
  apr_bps         integer     NOT NULL CHECK (apr_bps BETWEEN 0 AND 6000),
  tenure_months   integer     NOT NULL CHECK (tenure_months BETWEEN 1 AND 480),
  start_date      date        NOT NULL,
  emi_paise       bigint      NOT NULL CHECK (emi_paise > 0),
  status          text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'closed')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  version         integer     NOT NULL DEFAULT 0
);
CREATE INDEX liabilities_list ON planning.liabilities (account_id, created_at DESC, id DESC);

ALTER TABLE planning.liabilities ENABLE ROW LEVEL SECURITY;
ALTER TABLE planning.liabilities FORCE  ROW LEVEL SECURITY;
CREATE POLICY liabilities_tenant ON planning.liabilities FOR ALL TO planning_app
  USING      (account_id = (SELECT planning.app_account()))
  WITH CHECK (account_id = (SELECT planning.app_account()));
CREATE POLICY liabilities_owner ON planning.liabilities FOR ALL TO planning_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON planning.liabilities TO planning_app;

-- ---------------------------------------------------------------- the amortization schedule
-- Written once with the liability and never updated: the instalments are what the terms implied on the day it was
-- recorded. Closing a liability leaves its schedule in place.
CREATE TABLE planning.liability_schedule (
  liability_id            uuid     NOT NULL REFERENCES planning.liabilities (id) ON DELETE CASCADE,
  account_id              uuid     NOT NULL,
  installment_no          integer  NOT NULL CHECK (installment_no BETWEEN 1 AND 480),
  due_on                  date     NOT NULL,
  principal_paise         bigint   NOT NULL CHECK (principal_paise >= 0),
  interest_paise          bigint   NOT NULL CHECK (interest_paise >= 0),
  total_paise             bigint   NOT NULL CHECK (total_paise >= 0),
  outstanding_after_paise bigint   NOT NULL CHECK (outstanding_after_paise >= 0),
  PRIMARY KEY (liability_id, installment_no)
);

ALTER TABLE planning.liability_schedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE planning.liability_schedule FORCE  ROW LEVEL SECURITY;
CREATE POLICY liability_schedule_tenant ON planning.liability_schedule FOR ALL TO planning_app
  USING      (account_id = (SELECT planning.app_account()))
  WITH CHECK (account_id = (SELECT planning.app_account()));
CREATE POLICY liability_schedule_owner ON planning.liability_schedule FOR ALL TO planning_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON planning.liability_schedule TO planning_app;

-- ---------------------------------------------------------------- scenarios (copy-on-read, never a live view)
-- base_snapshot is what the account looked like when the scenario was saved: the budgets and liabilities that
-- mattered, copied in. Evaluating applies adjustments to that copy, so a scenario answers the same question
-- tomorrow as it did today (MASTER_PLAN P13).
CREATE TABLE planning.scenarios (
  id            uuid        PRIMARY KEY,
  account_id    uuid        NOT NULL,
  name          text        NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
  base_snapshot jsonb       NOT NULL,
  adjustments   jsonb       NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(adjustments) = 'array'),
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  version       integer     NOT NULL DEFAULT 0,
  CONSTRAINT scenarios_snapshot_is_an_object CHECK (jsonb_typeof(base_snapshot) = 'object')
);
CREATE INDEX scenarios_list ON planning.scenarios (account_id, created_at DESC, id DESC);

ALTER TABLE planning.scenarios ENABLE ROW LEVEL SECURITY;
ALTER TABLE planning.scenarios FORCE  ROW LEVEL SECURITY;
CREATE POLICY scenarios_tenant ON planning.scenarios FOR ALL TO planning_app
  USING      (account_id = (SELECT planning.app_account()))
  WITH CHECK (account_id = (SELECT planning.app_account()));
CREATE POLICY scenarios_owner ON planning.scenarios FOR ALL TO planning_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON planning.scenarios TO planning_app;

-- ---------------------------------------------------------------- statistics
-- The expense consumer looks up an account's active budgets on every event; the planner should know an account has
-- few of them rather than assuming the default correlation.
ALTER TABLE planning.budgets ALTER COLUMN account_id SET STATISTICS 1000;
ALTER TABLE planning.budget_period_spend ALTER COLUMN account_id SET STATISTICS 1000;
