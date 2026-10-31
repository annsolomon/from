-- P14 Reco A (MASTER_PLAN P14): the spend profile built from ledger events, the held-card and catalog projections,
-- the recompute state machine, and the latest recommendation plus its history.
-- Runs as reco_owner after V202610311000__platform.
--
-- Query -> index (BUILD_README §4, P14)
--   the scorer's spend vector for an account     PRIMARY KEY (account_id, category_root, window_code) spend_profile_pkey
--   a month's totals per root (window rebuild)   PRIMARY KEY (account_id, category_root, month)       spend_month_pkey
--   the account's held cards                     PRIMARY KEY (account_id, user_card_id)               held_cards_proj_pkey
--   every published card the scorer considers    (status) WHERE published                             catalog_cards_proj_published
--   a card's reward rules                        PRIMARY KEY (card_product_id, rule_id)               catalog_rules_proj_pkey
--   the sweeper's claim of dirty accounts        (dirty_since, account_id) WHERE dirty                rec_state_dirty
--   GET /v1/recommendations                      PRIMARY KEY (account_id)                             recommendations_pkey
--   one account's history, newest first          (account_id, computed_at DESC) per partition         recommendation_history_acct

-- ---------------------------------------------------------------- shared predicate: the projector actor
-- The catalog projections are global: written only by the event consumer, whose transaction carries the actor role
-- PROJECTOR (a role no token carries), and read by every tenant. Same shape as portfolio's (ADR-0016, A-64).
CREATE FUNCTION reco.is_projector() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT coalesce(reco.app_actor_role() = 'PROJECTOR', false) $$;
REVOKE ALL ON FUNCTION reco.is_projector() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION reco.is_projector() TO reco_app, reco_readonly;

-- ---------------------------------------------------------------- spend per month and per window
-- A rolling window cannot be maintained by deltas alone: an expense leaves the 12m window because time passed, not
-- because an event arrived. So the deltas land in spend_month (exact, append-and-adjust) and spend_profile is
-- recomputed for the touched root from it (A-78). `month` is the first day of the account's local (IST) month.
CREATE TABLE reco.spend_month (
  account_id    uuid         NOT NULL,
  category_root public.ltree NOT NULL CHECK (public.nlevel(category_root) = 1),
  month         date         NOT NULL CHECK (date_trunc('month', month) = month),
  total_paise   bigint       NOT NULL DEFAULT 0 CHECK (total_paise >= 0),
  txn_count     integer      NOT NULL DEFAULT 0 CHECK (txn_count >= 0),
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, category_root, month)
);
ALTER TABLE reco.spend_month ENABLE ROW LEVEL SECURITY;
ALTER TABLE reco.spend_month FORCE  ROW LEVEL SECURITY;
CREATE POLICY spend_month_tenant ON reco.spend_month FOR ALL TO reco_app
  USING      (account_id = (SELECT reco.app_account()))
  WITH CHECK (account_id = (SELECT reco.app_account()));
CREATE POLICY spend_month_owner ON reco.spend_month FOR ALL TO reco_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON reco.spend_month TO reco_app;

-- The scorer's spend vector: one row per root per window, as MASTER_PLAN P14 names it. projected_version records
-- the expense version last applied to the root; it is NOT a skip guard (one row aggregates many expenses with
-- independent version sequences - the inbox is the guard, ADR-0019 decision 1 and ADR-0020).
CREATE TABLE reco.spend_profile (
  account_id        uuid         NOT NULL,
  category_root     public.ltree NOT NULL CHECK (public.nlevel(category_root) = 1),
  -- WINDOW is a reserved word in Postgres, so the column is window_code; the API field stays `window` (A-80)
  window_code       text         NOT NULL CHECK (window_code IN ('3m', '6m', '12m')),
  monthly_avg_paise bigint       NOT NULL CHECK (monthly_avg_paise >= 0),
  total_paise       bigint       NOT NULL CHECK (total_paise >= 0),
  txn_count         integer      NOT NULL CHECK (txn_count >= 0),
  projected_version bigint       NOT NULL DEFAULT 0 CHECK (projected_version >= 0),
  updated_at        timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, category_root, window_code)
);
ALTER TABLE reco.spend_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE reco.spend_profile FORCE  ROW LEVEL SECURITY;
CREATE POLICY spend_profile_tenant ON reco.spend_profile FOR ALL TO reco_app
  USING      (account_id = (SELECT reco.app_account()))
  WITH CHECK (account_id = (SELECT reco.app_account()));
CREATE POLICY spend_profile_owner ON reco.spend_profile FOR ALL TO reco_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON reco.spend_profile TO reco_app;

-- ---------------------------------------------------------------- held cards (per account)
-- From dexter.portfolio.card.v1. A removed card is deleted from the projection: "which cards does this account
-- hold" is the only question asked of it, and portfolio keeps the history.
CREATE TABLE reco.held_cards_proj (
  account_id        uuid        NOT NULL,
  user_card_id      uuid        NOT NULL,
  card_product_id   uuid        NOT NULL,
  projected_version bigint      NOT NULL CHECK (projected_version >= 0),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, user_card_id)
);
ALTER TABLE reco.held_cards_proj ENABLE ROW LEVEL SECURITY;
ALTER TABLE reco.held_cards_proj FORCE  ROW LEVEL SECURITY;
CREATE POLICY held_cards_proj_tenant ON reco.held_cards_proj FOR ALL TO reco_app
  USING      (account_id = (SELECT reco.app_account()))
  WITH CHECK (account_id = (SELECT reco.app_account()));
CREATE POLICY held_cards_proj_owner ON reco.held_cards_proj FOR ALL TO reco_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON reco.held_cards_proj TO reco_app;

-- ---------------------------------------------------------------- catalog projection (global)
-- From the compacted dexter.catalog.card.v1. The card's own terms live here and its reward rules in
-- catalog_rules_proj, one row per rule, so the scorer reads rules without parsing JSON per card (A-79).
CREATE TABLE reco.catalog_cards_proj (
  card_product_id        uuid        PRIMARY KEY,
  slug                   text        NOT NULL,
  name                   text        NOT NULL,
  issuer_code            text        NOT NULL,
  network                text        NOT NULL,
  tier                   text        NOT NULL,
  annual_fee_paise       bigint      NOT NULL DEFAULT 0 CHECK (annual_fee_paise >= 0),
  fee_waiver_spend_paise bigint      CHECK (fee_waiver_spend_paise IS NULL OR fee_waiver_spend_paise > 0),
  milestone              jsonb       CHECK (milestone IS NULL OR jsonb_typeof(milestone) = 'object'),
  status                 text        NOT NULL CHECK (status IN ('published', 'retired')),
  current_version        integer     NOT NULL CHECK (current_version >= 1),
  projected_version      bigint      NOT NULL CHECK (projected_version >= 0),
  updated_at             timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX catalog_cards_proj_published ON reco.catalog_cards_proj (card_product_id)
  WHERE status = 'published';
ALTER TABLE reco.catalog_cards_proj ENABLE ROW LEVEL SECURITY;
ALTER TABLE reco.catalog_cards_proj FORCE  ROW LEVEL SECURITY;
CREATE POLICY catalog_cards_proj_read ON reco.catalog_cards_proj FOR SELECT TO reco_app
  USING ((SELECT reco.app_account()) IS NOT NULL);
CREATE POLICY catalog_cards_proj_insert ON reco.catalog_cards_proj FOR INSERT TO reco_app
  WITH CHECK ((SELECT reco.is_projector()));
CREATE POLICY catalog_cards_proj_update ON reco.catalog_cards_proj FOR UPDATE TO reco_app
  USING ((SELECT reco.is_projector())) WITH CHECK ((SELECT reco.is_projector()));
CREATE POLICY catalog_cards_proj_owner ON reco.catalog_cards_proj FOR ALL TO reco_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON reco.catalog_cards_proj TO reco_app;

-- A card's reward rules, replaced wholesale when a newer snapshot lands (a version brings new rule ids).
-- category_path NULL = the rule earns on everything the excluded paths do not remove.
CREATE TABLE reco.catalog_rules_proj (
  card_product_id          uuid         NOT NULL,
  rule_id                  uuid         NOT NULL,
  category_path            public.ltree CHECK (category_path IS NULL OR public.nlevel(category_path) BETWEEN 1 AND 4),
  merchant_id              uuid,
  reward_type              text         NOT NULL CHECK (reward_type IN ('cashback', 'points', 'miles')),
  rate_bps                 integer      NOT NULL CHECK (rate_bps BETWEEN 0 AND 100000),
  point_value_milli_paise  bigint       NOT NULL DEFAULT 0 CHECK (point_value_milli_paise >= 0),
  cap_paise                bigint       CHECK (cap_paise IS NULL OR cap_paise > 0),
  cap_period               text         CHECK (cap_period IS NULL
                                          OR cap_period IN ('txn', 'statement', 'month', 'quarter', 'year')),
  min_txn_paise            bigint       NOT NULL DEFAULT 0 CHECK (min_txn_paise >= 0),
  excluded_paths           text[]       NOT NULL DEFAULT '{}',
  priority                 integer      NOT NULL DEFAULT 0,
  projected_version        bigint       NOT NULL CHECK (projected_version >= 0),
  PRIMARY KEY (card_product_id, rule_id),
  CONSTRAINT catalog_rules_proj_cap_shape CHECK ((cap_paise IS NULL) = (cap_period IS NULL))
);
ALTER TABLE reco.catalog_rules_proj ENABLE ROW LEVEL SECURITY;
ALTER TABLE reco.catalog_rules_proj FORCE  ROW LEVEL SECURITY;
CREATE POLICY catalog_rules_proj_read ON reco.catalog_rules_proj FOR SELECT TO reco_app
  USING ((SELECT reco.app_account()) IS NOT NULL);
CREATE POLICY catalog_rules_proj_insert ON reco.catalog_rules_proj FOR INSERT TO reco_app
  WITH CHECK ((SELECT reco.is_projector()));
CREATE POLICY catalog_rules_proj_update ON reco.catalog_rules_proj FOR UPDATE TO reco_app
  USING ((SELECT reco.is_projector())) WITH CHECK ((SELECT reco.is_projector()));
CREATE POLICY catalog_rules_proj_delete ON reco.catalog_rules_proj FOR DELETE TO reco_app
  USING ((SELECT reco.is_projector()));
CREATE POLICY catalog_rules_proj_owner ON reco.catalog_rules_proj FOR ALL TO reco_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON reco.catalog_rules_proj TO reco_app;

-- ---------------------------------------------------------------- the recompute state machine
-- One row per account that reco has ever seen an event for. The three version columns are the high-water mark of
-- each source, so a recompute can record what it saw; `dirty` plus `dirty_since` is what the sweeper claims.
CREATE TABLE reco.rec_state (
  account_id   uuid        PRIMARY KEY,
  ledger_ver   bigint      NOT NULL DEFAULT 0 CHECK (ledger_ver >= 0),
  portfolio_ver bigint     NOT NULL DEFAULT 0 CHECK (portfolio_ver >= 0),
  catalog_ver  bigint      NOT NULL DEFAULT 0 CHECK (catalog_ver >= 0),
  dirty        boolean     NOT NULL DEFAULT true,
  dirty_since  timestamptz,
  computed_at  timestamptz,
  attempts     integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  CONSTRAINT rec_state_dirty_since CHECK (dirty = (dirty_since IS NOT NULL))
);
-- the sweeper's claim: the oldest dirty accounts first, index-only
CREATE INDEX rec_state_dirty ON reco.rec_state (dirty_since, account_id) WHERE dirty;
ALTER TABLE reco.rec_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE reco.rec_state FORCE  ROW LEVEL SECURITY;
-- the tenant sees its own row; the sweep is cross-account and goes through claim_dirty below
CREATE POLICY rec_state_tenant ON reco.rec_state FOR ALL TO reco_app
  USING      (account_id = (SELECT reco.app_account()))
  WITH CHECK (account_id = (SELECT reco.app_account()));
CREATE POLICY rec_state_owner ON reco.rec_state FOR ALL TO reco_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON reco.rec_state TO reco_app;

-- The sweeper's claim, as the owner. A sweep spans accounts by definition, and rec_state_tenant shows the app pool
-- exactly one row - its own - so the app pool cannot do this claim itself however the job is written (A-84; the
-- first cut tried it under a synthetic SWEEPER tenant and claimed nothing, every time). SECURITY DEFINER keeps the
-- cross-account reach inside one function that can only ever do this one thing, which is how portfolio's
-- close_ended_statements solved the same problem for its statement closer rather than opening the table up with a
-- second policy.
CREATE FUNCTION reco.claim_dirty(p_before timestamptz, p_limit integer) RETURNS SETOF uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS
$$
BEGIN
  IF p_before IS NULL OR p_limit NOT BETWEEN 1 AND 10000 THEN
    RAISE EXCEPTION 'bad claim arguments';
  END IF;
  -- claim and clear in one statement: SKIP LOCKED means a second sweeper cannot pick up an account the first is
  -- still recomputing, so every instance can sweep at once without leader election.
  RETURN QUERY
  WITH claimed AS (
    SELECT account_id FROM reco.rec_state
     WHERE dirty AND dirty_since <= p_before
     ORDER BY dirty_since, account_id
     LIMIT p_limit
     FOR UPDATE SKIP LOCKED
  ), cleared AS (
    UPDATE reco.rec_state s SET dirty = false, dirty_since = NULL
      FROM claimed c WHERE s.account_id = c.account_id
      RETURNING s.account_id
  )
  SELECT cleared.account_id FROM cleared;
END
$$;
REVOKE ALL ON FUNCTION reco.claim_dirty(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION reco.claim_dirty(timestamptz, integer) TO reco_app;

-- ---------------------------------------------------------------- the latest result, and the history
-- state_hash is the fingerprint of the inputs the result was computed from: equal hash = nothing to recompute.
CREATE TABLE reco.recommendations (
  account_id  uuid        PRIMARY KEY,
  state_hash  text        NOT NULL CHECK (length(state_hash) BETWEEN 1 AND 64),
  computed_at timestamptz NOT NULL DEFAULT now(),
  payload     jsonb       NOT NULL CHECK (jsonb_typeof(payload) = 'object')
);
ALTER TABLE reco.recommendations ENABLE ROW LEVEL SECURITY;
ALTER TABLE reco.recommendations FORCE  ROW LEVEL SECURITY;
CREATE POLICY recommendations_tenant ON reco.recommendations FOR ALL TO reco_app
  USING      (account_id = (SELECT reco.app_account()))
  WITH CHECK (account_id = (SELECT reco.app_account()));
CREATE POLICY recommendations_owner ON reco.recommendations FOR ALL TO reco_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON reco.recommendations TO reco_app;

-- Monthly partitions, 180-day retention (MASTER_PLAN P14). The relay's maintenance creates partitions ahead and
-- drops the old ones: create_partitions_ahead is SECURITY DEFINER and granted to reco_relay only, so the app
-- cannot reach it.
CREATE TABLE reco.recommendation_history (
  account_id  uuid        NOT NULL,
  computed_at timestamptz NOT NULL DEFAULT now(),
  state_hash  text        NOT NULL,
  payload     jsonb       NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  PRIMARY KEY (account_id, computed_at)
) PARTITION BY RANGE (computed_at);
CREATE INDEX recommendation_history_acct ON reco.recommendation_history (account_id, computed_at DESC);
ALTER TABLE reco.recommendation_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE reco.recommendation_history FORCE  ROW LEVEL SECURITY;
CREATE POLICY recommendation_history_tenant ON reco.recommendation_history FOR ALL TO reco_app
  USING      (account_id = (SELECT reco.app_account()))
  WITH CHECK (account_id = (SELECT reco.app_account()));
CREATE POLICY recommendation_history_owner ON reco.recommendation_history FOR ALL TO reco_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON reco.recommendation_history TO reco_app;
GRANT SELECT, DELETE ON reco.recommendation_history TO reco_relay;
SELECT reco.create_partitions_ahead('recommendation_history', 'month', 2);

-- statistics the planner needs for the sweeper's claim and the per-account reads
ALTER TABLE reco.spend_month      ALTER COLUMN account_id SET STATISTICS 1000;
ALTER TABLE reco.spend_profile    ALTER COLUMN account_id SET STATISTICS 1000;
ALTER TABLE reco.held_cards_proj  ALTER COLUMN account_id SET STATISTICS 1000;
