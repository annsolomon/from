-- P17 Commerce (MASTER_PLAN P17): marketplace deals, signed click redirects with raw clicks and maintained counters,
-- affiliate conversions, card applications, and the three projections the feed and the ads read.
-- Runs as commerce_owner after V202611081200__platform.
--   commerce_app    user and public requests, the click logger and counter flusher (actor TRACKER), the event
--                   consumers (actor PROJECTOR for the global card projection, USER for the per-account ones)
--   commerce_admin  /v1/admin/commerce/**: deals, campaigns, application decisions, stats (operator roles only)
--   commerce_relay  outbox, partitions of deal_clicks / ad_impressions_raw, inbox + Idempotency-Key purge
--
-- Query -> index (BUILD_README §4, P17)
--   GET /v1/deals: the bounded active set              (priority DESC, id) WHERE status = 'active'          deals_active
--   GET /v1/deals/{id}                                 PRIMARY KEY (id)                                      deals_pkey
--   admin deal list, newest first                      (created_at DESC, id DESC)                            deals_admin
--   GET /r/{token}: the click row                      PRIMARY KEY (created_at, id) per daily partition      deal_clicks_pkey
--   postback -> its click (id + created_at from UUIDv7) PRIMARY KEY (created_at, id), one partition pruned    deal_clicks_pkey
--   counter flush upsert / admin stats by day range    PRIMARY KEY (deal_id, day)                            deal_click_counters_pkey
--   postback replay                                    UNIQUE (network, provider_ref)                        conversions_network_provider_ref_key
--   conversions of a click                             (click_id) WHERE click_id IS NOT NULL                 conversions_click
--   one open application per card                      UNIQUE (account_id, card_product_id) WHERE open       card_applications_one_open
--   GET /v1/card-applications, newest first            (account_id, created_at DESC, id DESC)                card_applications_list
--   admin queue of open applications, oldest first     (created_at, id) WHERE open                           card_applications_queue
--   the account's held card products (card-match)      PRIMARY KEY (account_id, user_card_id)                held_cards_proj_pkey
--   is this card product applicable                    PRIMARY KEY (card_product_id)                         card_products_proj_pkey
--   is this account ad-free                            PRIMARY KEY (account_id)                              ad_free_proj_pkey

-- ---------------------------------------------------------------- actor predicates
-- Three actors no token carries, set by TenantTx for the code paths that need them, and one set of token roles:
--   PROJECTOR  the catalog consumer writing the global card projection (ADR-0016's shape)
--   TRACKER    the click/impression logger, the postback handler and the counter flusher: system work that spans
--              accounts by nature (a click carries at most the clicker's id; a postback arrives with none)
--   operators  SUPPORT / CATALOG_EDITOR / SUPER_ADMIN on the admin pool (the verified admin token's role)
CREATE FUNCTION commerce.is_projector() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT coalesce(commerce.app_actor_role() = 'PROJECTOR', false) $$;
CREATE FUNCTION commerce.is_tracker() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT coalesce(commerce.app_actor_role() = 'TRACKER', false) $$;
CREATE FUNCTION commerce.is_operator() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT coalesce(commerce.app_actor_role() IN ('SUPPORT', 'CATALOG_EDITOR', 'SUPER_ADMIN'), false) $$;
REVOKE ALL ON FUNCTION commerce.is_projector() FROM PUBLIC;
REVOKE ALL ON FUNCTION commerce.is_tracker() FROM PUBLIC;
REVOKE ALL ON FUNCTION commerce.is_operator() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION commerce.is_projector(), commerce.is_tracker(), commerce.is_operator()
  TO commerce_app, commerce_admin, commerce_readonly;

-- The admin pool writes events (application decisions) like the app pool does.
GRANT INSERT ON commerce.outbox TO commerce_admin;

-- ---------------------------------------------------------------- deals
-- Global marketplace data, written only by operators on the admin pool. The public (and every user) reads what is
-- not a draft; the services filter to active-and-valid. `components` is the saving's shape: each element is
--   {"kind": "percent", "valueBps": 1000, "capPaise": 500000}   or   {"kind": "flat", "valuePaise": 20000}
-- (optional "minSpendPaise" on either), each capped separately against the same spend (Savings, the P17 golden).
-- `eligible_card_products` empty = any card. Card-match ranks, it never filters (MASTER_PLAN P17).
CREATE TABLE commerce.deals (
  id                     uuid         PRIMARY KEY,
  merchant_id            uuid         NOT NULL,
  merchant_name          text         NOT NULL CHECK (length(merchant_name) BETWEEN 1 AND 80),
  title                  text         NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
  description            text         NOT NULL DEFAULT '' CHECK (length(description) <= 1000),
  category_path          public.ltree NOT NULL CHECK (public.nlevel(category_path) BETWEEN 1 AND 4),
  components             jsonb        NOT NULL CHECK (jsonb_typeof(components) = 'array'
                                        AND jsonb_array_length(components) BETWEEN 1 AND 5),
  eligible_card_products uuid[]       NOT NULL DEFAULT '{}' CHECK (cardinality(eligible_card_products) <= 50),
  reference_spend_paise  bigint       NOT NULL CHECK (reference_spend_paise BETWEEN 1 AND 100000000000),
  valid                  tstzrange    NOT NULL CHECK (NOT isempty(valid) AND NOT lower_inf(valid)),
  status                 text         NOT NULL CHECK (status IN ('draft', 'active', 'paused', 'ended')),
  priority               integer      NOT NULL DEFAULT 0 CHECK (priority BETWEEN -100 AND 100),
  sponsored              boolean      NOT NULL DEFAULT false,
  sponsor_weight         integer      NOT NULL DEFAULT 0 CHECK (sponsor_weight BETWEEN 0 AND 100),
  network                text         NOT NULL CHECK (network ~ '^[a-z][a-z0-9-]{1,30}$'),
  landing_url            text         NOT NULL CHECK (landing_url ~ '^https://[^[:space:]]{4,}$' AND length(landing_url) <= 2000),
  commission_bps         integer      NOT NULL DEFAULT 0 CHECK (commission_bps BETWEEN 0 AND 10000),
  version                integer      NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_by             uuid,
  created_at             timestamptz  NOT NULL DEFAULT now(),
  updated_at             timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT deals_sponsor_shape CHECK (sponsored OR sponsor_weight = 0)
);
CREATE INDEX deals_active ON commerce.deals (priority DESC, id) WHERE status = 'active';
CREATE INDEX deals_admin ON commerce.deals (created_at DESC, id DESC);
ALTER TABLE commerce.deals ENABLE ROW LEVEL SECURITY;
ALTER TABLE commerce.deals FORCE  ROW LEVEL SECURITY;
CREATE POLICY deals_read ON commerce.deals FOR SELECT TO commerce_app, commerce_readonly
  USING (status <> 'draft');
CREATE POLICY deals_operator ON commerce.deals FOR ALL TO commerce_admin
  USING ((SELECT commerce.is_operator())) WITH CHECK ((SELECT commerce.is_operator()));
CREATE POLICY deals_owner ON commerce.deals FOR ALL TO commerce_owner USING (true) WITH CHECK (true);
GRANT SELECT ON commerce.deals TO commerce_app, commerce_readonly;
GRANT SELECT, INSERT, UPDATE ON commerce.deals TO commerce_admin;

-- ---------------------------------------------------------------- raw clicks (partitioned daily)
-- One row per GET /r/{token} that redirected. id is a UUIDv7 and created_at is exactly its timestamp, so a
-- postback carrying the click id finds its row in one partition by primary key (no global index on id).
-- account_id is the token's (who saw the link), never a header. ip/ua are SHA-256 with a server-side salt.
CREATE TABLE commerce.deal_clicks (
  id               uuid        NOT NULL,
  created_at       timestamptz NOT NULL,
  account_id       uuid,
  deal_id          uuid        NOT NULL,
  click_token_hash bytea       NOT NULL CHECK (length(click_token_hash) = 32),
  ip_hash          bytea       NOT NULL CHECK (length(ip_hash) = 32),
  ua_hash          bytea       NOT NULL CHECK (length(ua_hash) = 32),
  PRIMARY KEY (created_at, id)
) PARTITION BY RANGE (created_at);
ALTER TABLE commerce.deal_clicks ENABLE ROW LEVEL SECURITY;
ALTER TABLE commerce.deal_clicks FORCE  ROW LEVEL SECURITY;
CREATE POLICY deal_clicks_tracker ON commerce.deal_clicks FOR ALL TO commerce_app
  USING ((SELECT commerce.is_tracker())) WITH CHECK ((SELECT commerce.is_tracker()));
CREATE POLICY deal_clicks_own ON commerce.deal_clicks FOR SELECT TO commerce_app
  USING (account_id = (SELECT commerce.app_account()));
CREATE POLICY deal_clicks_owner ON commerce.deal_clicks FOR ALL TO commerce_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON commerce.deal_clicks TO commerce_app;
SELECT commerce.create_partitions_ahead('deal_clicks', 'day', 3);

-- ---------------------------------------------------------------- click counters (MASTER_PLAN 8.3)
-- Redis takes the hot increments (INCR per click, SET NX per visitor-day for uniques); the flusher drains them here
-- every 10 s as additive upserts. `day` is the IST day. Conversions and approved commission are added by the
-- postback in its own transaction, so the admin stats read one maintained table and never COUNT the raw rows.
CREATE TABLE commerce.deal_click_counters (
  deal_id          uuid        NOT NULL,
  day              date        NOT NULL,
  clicks           bigint      NOT NULL DEFAULT 0 CHECK (clicks >= 0),
  uniques          bigint      NOT NULL DEFAULT 0 CHECK (uniques >= 0),
  conversions      bigint      NOT NULL DEFAULT 0 CHECK (conversions >= 0),
  commission_paise bigint      NOT NULL DEFAULT 0 CHECK (commission_paise >= 0),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (deal_id, day)
);
ALTER TABLE commerce.deal_click_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE commerce.deal_click_counters FORCE  ROW LEVEL SECURITY;
CREATE POLICY deal_click_counters_tracker ON commerce.deal_click_counters FOR ALL TO commerce_app
  USING ((SELECT commerce.is_tracker())) WITH CHECK ((SELECT commerce.is_tracker()));
CREATE POLICY deal_click_counters_operator ON commerce.deal_click_counters FOR SELECT TO commerce_admin
  USING ((SELECT commerce.is_operator()));
CREATE POLICY deal_click_counters_owner ON commerce.deal_click_counters FOR ALL TO commerce_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON commerce.deal_click_counters TO commerce_app;
GRANT SELECT ON commerce.deal_click_counters TO commerce_admin;

-- ---------------------------------------------------------------- conversions (affiliate postbacks)
-- Idempotent by (network, provider_ref): a replayed postback changes nothing, a later one moves the status
-- forward (pending -> approved | rejected; decided is final). click_id is the network's subid when it names a
-- click this service logged; NULL when it does not (kept, so the network's numbers still reconcile).
CREATE TABLE commerce.conversions (
  id               uuid        PRIMARY KEY,
  network          text        NOT NULL CHECK (network ~ '^[a-z][a-z0-9-]{1,30}$'),
  provider_ref     text        NOT NULL CHECK (provider_ref ~ '^[A-Za-z0-9._:-]{1,100}$'),
  click_id         uuid,
  deal_id          uuid,
  account_id       uuid,
  amount_paise     bigint      NOT NULL CHECK (amount_paise BETWEEN 0 AND 100000000000),
  commission_paise bigint      NOT NULL CHECK (commission_paise BETWEEN 0 AND 100000000000),
  status           text        NOT NULL CHECK (status IN ('pending', 'approved', 'rejected')),
  occurred_at      timestamptz NOT NULL,
  version          integer     NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (network, provider_ref)
);
CREATE INDEX conversions_click ON commerce.conversions (click_id) WHERE click_id IS NOT NULL;
ALTER TABLE commerce.conversions ENABLE ROW LEVEL SECURITY;
ALTER TABLE commerce.conversions FORCE  ROW LEVEL SECURITY;
CREATE POLICY conversions_tracker ON commerce.conversions FOR ALL TO commerce_app
  USING ((SELECT commerce.is_tracker())) WITH CHECK ((SELECT commerce.is_tracker()));
CREATE POLICY conversions_own ON commerce.conversions FOR SELECT TO commerce_app
  USING (account_id = (SELECT commerce.app_account()));
CREATE POLICY conversions_operator ON commerce.conversions FOR SELECT TO commerce_admin
  USING ((SELECT commerce.is_operator()));
CREATE POLICY conversions_owner ON commerce.conversions FOR ALL TO commerce_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON commerce.conversions TO commerce_app;
GRANT SELECT ON commerce.conversions TO commerce_admin;

-- ---------------------------------------------------------------- card applications
-- The user applies (Idempotency-Key required) and may withdraw; an operator moves it to in_review and decides.
-- status_history is the append-only trail of {status, at, by, reason} (a jsonb array: the plan's jsonb[]).
-- One OPEN application per (account, card): a decided or withdrawn one does not block a new one.
CREATE TABLE commerce.card_applications (
  id              uuid        PRIMARY KEY,
  account_id      uuid        NOT NULL,
  card_product_id uuid        NOT NULL,
  status          text        NOT NULL CHECK (status IN ('submitted', 'in_review', 'approved', 'rejected', 'withdrawn')),
  status_history  jsonb       NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(status_history) = 'array'),
  reason          text        CHECK (reason IS NULL OR length(reason) <= 500),
  version         integer     NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at      timestamptz NOT NULL,
  updated_at      timestamptz NOT NULL,
  decided_at      timestamptz,
  decided_by      uuid,
  CONSTRAINT card_applications_decided_shape
    CHECK ((status IN ('approved', 'rejected')) = (decided_at IS NOT NULL))
);
CREATE UNIQUE INDEX card_applications_one_open ON commerce.card_applications (account_id, card_product_id)
  WHERE status IN ('submitted', 'in_review');
CREATE INDEX card_applications_list ON commerce.card_applications (account_id, created_at DESC, id DESC);
CREATE INDEX card_applications_queue ON commerce.card_applications (created_at, id)
  WHERE status IN ('submitted', 'in_review');
ALTER TABLE commerce.card_applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE commerce.card_applications FORCE  ROW LEVEL SECURITY;
CREATE POLICY card_applications_tenant ON commerce.card_applications FOR ALL TO commerce_app
  USING      (account_id = (SELECT commerce.app_account()))
  WITH CHECK (account_id = (SELECT commerce.app_account()));
CREATE POLICY card_applications_operator ON commerce.card_applications FOR ALL TO commerce_admin
  USING ((SELECT commerce.is_operator())) WITH CHECK ((SELECT commerce.is_operator()));
CREATE POLICY card_applications_owner ON commerce.card_applications FOR ALL TO commerce_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON commerce.card_applications TO commerce_app;
GRANT SELECT, UPDATE ON commerce.card_applications TO commerce_admin;

-- ---------------------------------------------------------------- projections
-- Card products from the compacted dexter.catalog.card.v1 (global, PROJECTOR-written): what an application may
-- name, and the names the feed shows next to a deal's eligible cards.
CREATE TABLE commerce.card_products_proj (
  card_product_id   uuid        PRIMARY KEY,
  slug              text        NOT NULL,
  name              text        NOT NULL,
  issuer_code       text        NOT NULL,
  network           text        NOT NULL,
  status            text        NOT NULL CHECK (status IN ('published', 'retired')),
  projected_version bigint      NOT NULL CHECK (projected_version >= 0),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE commerce.card_products_proj ENABLE ROW LEVEL SECURITY;
ALTER TABLE commerce.card_products_proj FORCE  ROW LEVEL SECURITY;
CREATE POLICY card_products_proj_read ON commerce.card_products_proj FOR SELECT
  TO commerce_app, commerce_admin, commerce_readonly USING (true);
CREATE POLICY card_products_proj_insert ON commerce.card_products_proj FOR INSERT TO commerce_app
  WITH CHECK ((SELECT commerce.is_projector()));
CREATE POLICY card_products_proj_update ON commerce.card_products_proj FOR UPDATE TO commerce_app
  USING ((SELECT commerce.is_projector())) WITH CHECK ((SELECT commerce.is_projector()));
CREATE POLICY card_products_proj_owner ON commerce.card_products_proj FOR ALL TO commerce_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON commerce.card_products_proj TO commerce_app;
GRANT SELECT ON commerce.card_products_proj TO commerce_admin, commerce_readonly;

-- The account's held cards from dexter.portfolio.card.v1 (card-match). A removed card leaves the projection.
CREATE TABLE commerce.held_cards_proj (
  account_id        uuid        NOT NULL,
  user_card_id      uuid        NOT NULL,
  card_product_id   uuid        NOT NULL,
  projected_version bigint      NOT NULL CHECK (projected_version >= 0),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, user_card_id)
);
ALTER TABLE commerce.held_cards_proj ENABLE ROW LEVEL SECURITY;
ALTER TABLE commerce.held_cards_proj FORCE  ROW LEVEL SECURITY;
CREATE POLICY held_cards_proj_tenant ON commerce.held_cards_proj FOR ALL TO commerce_app
  USING      (account_id = (SELECT commerce.app_account()))
  WITH CHECK (account_id = (SELECT commerce.app_account()));
CREATE POLICY held_cards_proj_owner ON commerce.held_cards_proj FOR ALL TO commerce_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON commerce.held_cards_proj TO commerce_app;

-- Ad-free from dexter.billing.entitlements.v1 (the `adFree` entitlement; the event's per-account version orders it).
CREATE TABLE commerce.ad_free_proj (
  account_id        uuid        PRIMARY KEY,
  tier              text        NOT NULL,
  ad_free           boolean     NOT NULL,
  projected_version bigint      NOT NULL CHECK (projected_version >= 0),
  updated_at        timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE commerce.ad_free_proj ENABLE ROW LEVEL SECURITY;
ALTER TABLE commerce.ad_free_proj FORCE  ROW LEVEL SECURITY;
CREATE POLICY ad_free_proj_tenant ON commerce.ad_free_proj FOR ALL TO commerce_app
  USING      (account_id = (SELECT commerce.app_account()))
  WITH CHECK (account_id = (SELECT commerce.app_account()));
CREATE POLICY ad_free_proj_owner ON commerce.ad_free_proj FOR ALL TO commerce_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON commerce.ad_free_proj TO commerce_app;

-- statistics for the per-account reads
ALTER TABLE commerce.card_applications ALTER COLUMN account_id SET STATISTICS 1000;
ALTER TABLE commerce.held_cards_proj   ALTER COLUMN account_id SET STATISTICS 1000;
