-- P15 (Reco B): what best-card-now needs that the P14 profile cannot answer, and the offers projection.
--
-- Query -> index (BUILD_README §4, P15)
--   this month's spend on one held card              (account_id, user_card_id, month)       card_period_spend_pkey
--   the offers that could apply to a purchase        (status) partial, then per card          offers_proj_active
--
-- P14 built a per-category profile of monthly averages, which is the right input for "which card should I get".
-- It is the wrong input for "which card should I put this on": that question is about THIS month on THIS card -
-- how much of a cap is left - and an average over three months cannot answer it (A-86).

-- ---------------------------------------------------------------- per-card spend, per month
-- Deltas from the same ledger events the spend profile is built from: ExpenseStateV1 carries userCardId, so no new
-- event source is needed (A-86). Exact and append-and-adjust like reco.spend_month, floored at zero, and the inbox
-- is the exactly-once guard for the same reason (ADR-0020 decision 2). `month` is the first day of the account's
-- local (IST) month, so "this period" means what the cardholder's statement means.
--
-- What a rule has EARNED this month is not stored: it is computed from this spend by replaying the card's current
-- rules (Savings.monthlyEarnedByRule). Storing earned reward would freeze the rules as they were when the expense
-- arrived, and a republished card would then be scored against stale terms.
CREATE TABLE reco.card_period_spend (
  account_id    uuid         NOT NULL,
  user_card_id  uuid         NOT NULL,
  category_root public.ltree NOT NULL CHECK (public.nlevel(category_root) = 1),
  month         date         NOT NULL CHECK (date_trunc('month', month) = month),
  total_paise   bigint       NOT NULL DEFAULT 0 CHECK (total_paise >= 0),
  txn_count     integer      NOT NULL DEFAULT 0 CHECK (txn_count >= 0),
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, user_card_id, month, category_root)
);
ALTER TABLE reco.card_period_spend ENABLE ROW LEVEL SECURITY;
ALTER TABLE reco.card_period_spend FORCE  ROW LEVEL SECURITY;
CREATE POLICY card_period_spend_tenant ON reco.card_period_spend FOR ALL TO reco_app
  USING      (account_id = (SELECT reco.app_account()))
  WITH CHECK (account_id = (SELECT reco.app_account()));
CREATE POLICY card_period_spend_owner ON reco.card_period_spend FOR ALL TO reco_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON reco.card_period_spend TO reco_app;

-- ---------------------------------------------------------------- the offers projection
-- From dexter.catalog.offer.v1 (catalog.offer.changed, P09). Global like the card projections and written only
-- under the PROJECTOR actor, because an offer belongs to a card, not to an account. A withdrawn offer is kept with
-- status 'withdrawn' rather than deleted, so a late event cannot resurrect it and the version guard still applies.
CREATE TABLE reco.offers_proj (
  offer_id           uuid         PRIMARY KEY,
  card_product_id    uuid,
  merchant_id        uuid,
  category_path      public.ltree CHECK (category_path IS NULL OR public.nlevel(category_path) BETWEEN 1 AND 4),
  title              text         NOT NULL CHECK (length(title) BETWEEN 1 AND 160),
  kind               text         NOT NULL CHECK (kind IN ('pct', 'flat', 'cashback')),
  value_bps          integer      CHECK (value_bps IS NULL OR value_bps BETWEEN 1 AND 10000),
  value_paise        bigint       CHECK (value_paise IS NULL OR value_paise > 0),
  max_discount_paise bigint       CHECK (max_discount_paise IS NULL OR max_discount_paise > 0),
  min_spend_paise    bigint       NOT NULL DEFAULT 0 CHECK (min_spend_paise >= 0),
  valid_from         timestamptz,
  valid_to           timestamptz  CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to > valid_from),
  stackable          boolean      NOT NULL DEFAULT false,
  status             text         NOT NULL CHECK (status IN ('active', 'withdrawn')),
  current_version    integer      NOT NULL CHECK (current_version >= 0),
  projected_version  bigint       NOT NULL CHECK (projected_version >= 0),
  updated_at         timestamptz  NOT NULL DEFAULT now(),
  -- the same shape catalog.offers enforces: a percentage offer carries a rate, the other kinds carry an amount
  CONSTRAINT offers_proj_value_shape
    CHECK ((kind = 'pct') = (value_bps IS NOT NULL) AND (kind = 'pct') = (value_paise IS NULL))
);
-- best-card-now asks for the active offers of the cards the account holds, so the partial index carries the card
CREATE INDEX offers_proj_active ON reco.offers_proj (card_product_id, offer_id) WHERE status = 'active';
ALTER TABLE reco.offers_proj ENABLE ROW LEVEL SECURITY;
ALTER TABLE reco.offers_proj FORCE  ROW LEVEL SECURITY;
CREATE POLICY offers_proj_read ON reco.offers_proj FOR SELECT TO reco_app
  USING ((SELECT reco.app_account()) IS NOT NULL);
CREATE POLICY offers_proj_insert ON reco.offers_proj FOR INSERT TO reco_app
  WITH CHECK ((SELECT reco.is_projector()));
CREATE POLICY offers_proj_update ON reco.offers_proj FOR UPDATE TO reco_app
  USING ((SELECT reco.is_projector())) WITH CHECK ((SELECT reco.is_projector()));
CREATE POLICY offers_proj_owner ON reco.offers_proj FOR ALL TO reco_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON reco.offers_proj TO reco_app;
