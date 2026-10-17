-- P09 Catalog A (MASTER_PLAN P09): issuers, card products with drafts and immutable published versions, versioned
-- reward rules (no overlapping validity per scope), offers, milestones. Global data: no account column anywhere.
--   catalog_app    public reads: published cards, their current rules/milestones, active offers (no context needed)
--   catalog_admin  admin endpoints: everything, but only for actor role CATALOG_EDITOR / SUPER_ADMIN (token roles)
-- Runs as catalog_owner after V202610171500__platform.
--
-- Query -> index (BUILD_README §4, P09)
--   GET /v1/catalog/cards (name, id) keyset         (name, id) WHERE status = 'published'                     card_products_public
--     + network                                     (network, name, id) WHERE published                       card_products_network
--     + issuer                                      (issuer_id, name, id) WHERE published                     card_products_issuer
--     + maxAnnualFee / category earning ≥ X bps     residual filters on card_products_public (+ rules by card) card_products_public
--   GET /v1/catalog/cards/{slug}                    UNIQUE (slug)                                              card_products_slug_key
--   GET /v1/catalog/cards/search?q=                 GIN (lower(name) gin_trgm_ops) WHERE published             card_products_name_trgm
--   current rules of a card / of many cards         (card_product_id, version)                                reward_rules_card_version
--   categories/potential (rules matching a path)    GIST (category_path) + card join                          reward_rules_path
--   GET /v1/catalog/offers (active, merchant)       (merchant_id) WHERE status = 'active'                     offers_merchant
--   GET /v1/catalog/offers (active, category)       GIST (category_path) WHERE status = 'active'              offers_path
--   current version of a card                       (card_product_id) WHERE effective_to IS NULL (unique)      card_product_versions_current

-- ---------------------------------------------------------------- shared predicates
-- The admin pool may act only for the catalog roles carried by the verified admin token (TenantTx sets it).
CREATE FUNCTION catalog.is_editor() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT coalesce(catalog.app_actor_role() IN ('CATALOG_EDITOR', 'SUPER_ADMIN'), false) $$;
REVOKE ALL ON FUNCTION catalog.is_editor() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION catalog.is_editor() TO catalog_app, catalog_admin, catalog_readonly;

-- ---------------------------------------------------------------- issuers
CREATE TABLE catalog.issuers (
  id         uuid        PRIMARY KEY,
  code       text        NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  type       text        NOT NULL CHECK (type IN ('bank', 'nbfc', 'cobrand')),
  created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE catalog.issuers ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.issuers FORCE  ROW LEVEL SECURITY;
CREATE POLICY issuers_read ON catalog.issuers FOR SELECT TO catalog_app USING (true);
CREATE POLICY issuers_editor ON catalog.issuers FOR ALL TO catalog_admin
  USING ((SELECT catalog.is_editor())) WITH CHECK ((SELECT catalog.is_editor()));
CREATE POLICY issuers_owner ON catalog.issuers FOR ALL TO catalog_owner USING (true) WITH CHECK (true);
GRANT SELECT ON catalog.issuers TO catalog_app;
GRANT SELECT, INSERT ON catalog.issuers TO catalog_admin;

-- ---------------------------------------------------------------- card products (the published face + status)
-- The columns are the CURRENT PUBLISHED values (what filters and sorts use); edits live in card_product_drafts until
-- a publish copies them here, writes an immutable version and the rules of that version.
CREATE TABLE catalog.card_products (
  id                        uuid        PRIMARY KEY,
  issuer_id                 uuid        NOT NULL REFERENCES catalog.issuers (id),
  slug                      text        NOT NULL CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,79}$'),
  name                      text        NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  network                   text        NOT NULL CHECK (network IN ('visa', 'mastercard', 'rupay', 'amex', 'diners')),
  tier                      text        NOT NULL CHECK (length(tier) BETWEEN 1 AND 40),
  joining_fee_paise         bigint      NOT NULL DEFAULT 0 CHECK (joining_fee_paise >= 0),
  annual_fee_paise          bigint      NOT NULL DEFAULT 0 CHECK (annual_fee_paise >= 0),
  fee_waiver_spend_paise    bigint      CHECK (fee_waiver_spend_paise IS NULL OR fee_waiver_spend_paise >= 0),
  apr_bps                   integer     CHECK (apr_bps IS NULL OR apr_bps BETWEEN 0 AND 10000),
  forex_markup_bps          integer     CHECK (forex_markup_bps IS NULL OR forex_markup_bps BETWEEN 0 AND 10000),
  fuel_surcharge_waiver_bps integer     CHECK (fuel_surcharge_waiver_bps IS NULL OR fuel_surcharge_waiver_bps BETWEEN 0 AND 10000),
  features                  jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(features) = 'object'),
  status                    text        NOT NULL CHECK (status IN ('draft', 'published', 'retired')),
  current_version           integer     CHECK (current_version IS NULL OR current_version >= 1),
  content_hash              bytea,
  created_at                timestamptz NOT NULL DEFAULT now(),
  updated_at                timestamptz NOT NULL DEFAULT now(),
  version                   integer     NOT NULL DEFAULT 0,
  CONSTRAINT card_products_slug_key UNIQUE (slug),
  CONSTRAINT card_products_published_shape CHECK (status = 'draft' OR (current_version IS NOT NULL AND content_hash IS NOT NULL))
);
CREATE INDEX card_products_public ON catalog.card_products (name, id) WHERE status = 'published';
CREATE INDEX card_products_network ON catalog.card_products (network, name, id) WHERE status = 'published';
CREATE INDEX card_products_issuer ON catalog.card_products (issuer_id, name, id) WHERE status = 'published';
CREATE INDEX card_products_name_trgm ON catalog.card_products USING gin (lower(name) public.gin_trgm_ops)
  WHERE status = 'published';
CREATE INDEX card_products_admin ON catalog.card_products (name, id);
ALTER TABLE catalog.card_products ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.card_products FORCE  ROW LEVEL SECURITY;
CREATE POLICY card_products_public_read ON catalog.card_products FOR SELECT TO catalog_app USING (status = 'published');
CREATE POLICY card_products_editor ON catalog.card_products FOR ALL TO catalog_admin
  USING ((SELECT catalog.is_editor())) WITH CHECK ((SELECT catalog.is_editor()));
CREATE POLICY card_products_owner ON catalog.card_products FOR ALL TO catalog_owner USING (true) WITH CHECK (true);
GRANT SELECT ON catalog.card_products TO catalog_app;
GRANT SELECT, INSERT, UPDATE ON catalog.card_products TO catalog_admin;

-- ---------------------------------------------------------------- drafts (editor only)
CREATE TABLE catalog.card_product_drafts (
  card_product_id uuid        PRIMARY KEY REFERENCES catalog.card_products (id) ON DELETE CASCADE,
  document        jsonb       NOT NULL CHECK (jsonb_typeof(document) = 'object'),
  updated_by      uuid,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  version         integer     NOT NULL DEFAULT 0
);
ALTER TABLE catalog.card_product_drafts ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.card_product_drafts FORCE  ROW LEVEL SECURITY;
CREATE POLICY card_product_drafts_editor ON catalog.card_product_drafts FOR ALL TO catalog_admin
  USING ((SELECT catalog.is_editor())) WITH CHECK ((SELECT catalog.is_editor()));
CREATE POLICY card_product_drafts_owner ON catalog.card_product_drafts FOR ALL TO catalog_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON catalog.card_product_drafts TO catalog_admin;

-- ---------------------------------------------------------------- published versions (immutable)
CREATE TABLE catalog.card_product_versions (
  card_product_id uuid        NOT NULL REFERENCES catalog.card_products (id),
  version         integer     NOT NULL CHECK (version >= 1),
  snapshot        jsonb       NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
  effective_from  timestamptz NOT NULL,
  effective_to    timestamptz,
  PRIMARY KEY (card_product_id, version),
  CONSTRAINT card_product_versions_range CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE UNIQUE INDEX card_product_versions_current ON catalog.card_product_versions (card_product_id)
  WHERE effective_to IS NULL;

-- A published version never changes: only its open end may be closed, once (MASTER_PLAN P09 "immutable version").
CREATE FUNCTION catalog.card_product_versions_immutable() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'card_product_versions are immutable (delete)' USING ERRCODE = 'DX409';
  END IF;
  IF NEW.card_product_id IS DISTINCT FROM OLD.card_product_id OR NEW.version IS DISTINCT FROM OLD.version
     OR NEW.snapshot IS DISTINCT FROM OLD.snapshot OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
     OR OLD.effective_to IS NOT NULL THEN
    RAISE EXCEPTION 'card_product_versions are immutable (only an open effective_to may be closed)'
      USING ERRCODE = 'DX409';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER card_product_versions_immutable BEFORE UPDATE OR DELETE ON catalog.card_product_versions
  FOR EACH ROW EXECUTE FUNCTION catalog.card_product_versions_immutable();
ALTER TABLE catalog.card_product_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.card_product_versions FORCE  ROW LEVEL SECURITY;
CREATE POLICY card_product_versions_public_read ON catalog.card_product_versions FOR SELECT TO catalog_app
  USING (EXISTS (SELECT 1 FROM catalog.card_products c WHERE c.id = card_product_id AND c.status = 'published'));
CREATE POLICY card_product_versions_editor ON catalog.card_product_versions FOR ALL TO catalog_admin
  USING ((SELECT catalog.is_editor())) WITH CHECK ((SELECT catalog.is_editor()));
CREATE POLICY card_product_versions_owner ON catalog.card_product_versions FOR ALL TO catalog_owner
  USING (true) WITH CHECK (true);
GRANT SELECT ON catalog.card_product_versions TO catalog_app;
GRANT SELECT, INSERT, UPDATE ON catalog.card_product_versions TO catalog_admin;

-- ---------------------------------------------------------------- reward rules (per version, validity ranges)
CREATE TABLE catalog.reward_rules (
  id                      uuid         PRIMARY KEY,
  card_product_id         uuid         NOT NULL REFERENCES catalog.card_products (id),
  version                 integer      NOT NULL CHECK (version >= 1),
  category_path           public.ltree,
  merchant_id             uuid,
  reward_type             text         NOT NULL CHECK (reward_type IN ('cashback', 'points', 'miles')),
  rate_bps                integer      NOT NULL CHECK (rate_bps BETWEEN 0 AND 10000),
  point_value_milli_paise bigint       NOT NULL DEFAULT 0 CHECK (point_value_milli_paise >= 0),
  cap_paise               bigint       CHECK (cap_paise IS NULL OR cap_paise > 0),
  cap_period              text         CHECK (cap_period IS NULL OR cap_period IN ('txn', 'statement', 'month', 'quarter', 'year')),
  min_txn_paise           bigint       NOT NULL DEFAULT 0 CHECK (min_txn_paise >= 0),
  excluded_paths          public.ltree[],
  valid                   tstzrange    NOT NULL CHECK (NOT isempty(valid)),
  priority                integer      NOT NULL DEFAULT 100,
  CONSTRAINT reward_rules_cap_shape CHECK ((cap_paise IS NULL) = (cap_period IS NULL)),
  -- MASTER_PLAN P09: never two live rules for the same scope of a card. Scope = (category, merchant); NULL scopes
  -- (the base rate) are compared as '' / the nil uuid so they are covered too.
  CONSTRAINT reward_rules_no_overlap EXCLUDE USING gist (
    card_product_id WITH =,
    (coalesce(category_path::text, '')) WITH =,
    (coalesce(merchant_id, '00000000-0000-0000-0000-000000000000'::uuid)) WITH =,
    valid WITH &&)
);
CREATE INDEX reward_rules_card_version ON catalog.reward_rules (card_product_id, version);
CREATE INDEX reward_rules_path ON catalog.reward_rules USING gist (category_path);
ALTER TABLE catalog.reward_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.reward_rules FORCE  ROW LEVEL SECURITY;
CREATE POLICY reward_rules_public_read ON catalog.reward_rules FOR SELECT TO catalog_app
  USING (EXISTS (SELECT 1 FROM catalog.card_products c WHERE c.id = card_product_id AND c.status = 'published'));
CREATE POLICY reward_rules_editor ON catalog.reward_rules FOR ALL TO catalog_admin
  USING ((SELECT catalog.is_editor())) WITH CHECK ((SELECT catalog.is_editor()));
CREATE POLICY reward_rules_owner ON catalog.reward_rules FOR ALL TO catalog_owner USING (true) WITH CHECK (true);
GRANT SELECT ON catalog.reward_rules TO catalog_app;
GRANT SELECT, INSERT ON catalog.reward_rules TO catalog_admin;
GRANT UPDATE (valid) ON catalog.reward_rules TO catalog_admin;

-- ---------------------------------------------------------------- milestones (current version's)
CREATE TABLE catalog.milestones (
  card_product_id       uuid   NOT NULL REFERENCES catalog.card_products (id),
  spend_threshold_paise bigint NOT NULL CHECK (spend_threshold_paise > 0),
  period                text   NOT NULL CHECK (period IN ('statement', 'month', 'quarter', 'year')),
  benefit_type          text   NOT NULL CHECK (length(benefit_type) BETWEEN 1 AND 40),
  benefit_value_paise   bigint NOT NULL CHECK (benefit_value_paise >= 0),
  PRIMARY KEY (card_product_id, spend_threshold_paise, period)
);
ALTER TABLE catalog.milestones ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.milestones FORCE  ROW LEVEL SECURITY;
CREATE POLICY milestones_public_read ON catalog.milestones FOR SELECT TO catalog_app
  USING (EXISTS (SELECT 1 FROM catalog.card_products c WHERE c.id = card_product_id AND c.status = 'published'));
CREATE POLICY milestones_editor ON catalog.milestones FOR ALL TO catalog_admin
  USING ((SELECT catalog.is_editor())) WITH CHECK ((SELECT catalog.is_editor()));
CREATE POLICY milestones_owner ON catalog.milestones FOR ALL TO catalog_owner USING (true) WITH CHECK (true);
GRANT SELECT ON catalog.milestones TO catalog_app;
GRANT SELECT, INSERT, DELETE ON catalog.milestones TO catalog_admin;

-- ---------------------------------------------------------------- offers
CREATE TABLE catalog.offers (
  id                 uuid         PRIMARY KEY,
  issuer_id          uuid         NOT NULL REFERENCES catalog.issuers (id),
  card_product_id    uuid         REFERENCES catalog.card_products (id),
  merchant_id        uuid,
  category_path      public.ltree,
  title              text         NOT NULL CHECK (length(title) BETWEEN 1 AND 160),
  kind               text         NOT NULL CHECK (kind IN ('pct', 'flat', 'cashback')),
  value_bps          integer      CHECK (value_bps IS NULL OR value_bps BETWEEN 1 AND 10000),
  value_paise        bigint       CHECK (value_paise IS NULL OR value_paise > 0),
  max_discount_paise bigint       CHECK (max_discount_paise IS NULL OR max_discount_paise > 0),
  min_spend_paise    bigint       NOT NULL DEFAULT 0 CHECK (min_spend_paise >= 0),
  valid              tstzrange    NOT NULL CHECK (NOT isempty(valid)),
  stackable          boolean      NOT NULL DEFAULT false,
  terms_url          text         CHECK (terms_url IS NULL OR terms_url ~ '^https://'),
  status             text         NOT NULL CHECK (status IN ('active', 'withdrawn')),
  version            integer      NOT NULL DEFAULT 0,
  created_at         timestamptz  NOT NULL DEFAULT now(),
  updated_at         timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT offers_value_shape CHECK ((kind = 'pct') = (value_bps IS NOT NULL) AND (kind = 'pct') = (value_paise IS NULL))
);
CREATE INDEX offers_merchant ON catalog.offers (merchant_id) WHERE status = 'active';
CREATE INDEX offers_path ON catalog.offers USING gist (category_path) WHERE status = 'active';
ALTER TABLE catalog.offers ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.offers FORCE  ROW LEVEL SECURITY;
CREATE POLICY offers_public_read ON catalog.offers FOR SELECT TO catalog_app USING (status = 'active');
CREATE POLICY offers_editor ON catalog.offers FOR ALL TO catalog_admin
  USING ((SELECT catalog.is_editor())) WITH CHECK ((SELECT catalog.is_editor()));
CREATE POLICY offers_owner ON catalog.offers FOR ALL TO catalog_owner USING (true) WITH CHECK (true);
GRANT SELECT ON catalog.offers TO catalog_app;
GRANT SELECT, INSERT, UPDATE ON catalog.offers TO catalog_admin;

-- Admin endpoints write events (outbox) in their own transaction.
GRANT INSERT ON catalog.outbox TO catalog_admin;
