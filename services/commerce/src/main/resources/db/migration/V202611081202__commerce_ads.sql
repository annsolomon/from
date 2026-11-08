-- P17 Commerce, ads (MASTER_PLAN P17): placements, campaigns and their creatives (operators on the admin pool),
-- raw impressions (partitioned daily) and the maintained per-creative counters. Frequency caps live in Redis
-- (a per-campaign-per-visitor counter with the campaign's window as its TTL); ad-free comes from ad_free_proj.
-- Runs as commerce_owner after V202611081201__commerce_core.
--
-- Query -> index (BUILD_README §4, P17)
--   GET /v1/ads/serve?placement=: live campaigns     GIN (placements) WHERE status = 'active'               campaigns_active
--     + their active creatives                       (campaign_id) WHERE status = 'active'                  creatives_campaign_active
--   admin campaign list, newest first              (created_at DESC, id DESC)                             campaigns_admin
--   a campaign's creatives (admin)                 (campaign_id, created_at)                              creatives_campaign
--   impression row                                 PRIMARY KEY (created_at, id) per daily partition       ad_impressions_raw_pkey
--   counter flush upsert / stats by day range      PRIMARY KEY (creative_id, day)                         ad_counters_pkey

-- ---------------------------------------------------------------- placements (reference data)
CREATE TABLE commerce.placements (
  code        text PRIMARY KEY CHECK (code ~ '^[a-z][a-z0-9-]{1,30}$'),
  description text NOT NULL
);
INSERT INTO commerce.placements (code, description) VALUES
  ('home', 'Home feed banner'),
  ('deal-detail', 'Deal page sidebar'),
  ('dashboard', 'Dashboard card');
ALTER TABLE commerce.placements ENABLE ROW LEVEL SECURITY;
ALTER TABLE commerce.placements FORCE  ROW LEVEL SECURITY;
CREATE POLICY placements_read ON commerce.placements FOR SELECT
  TO commerce_app, commerce_admin, commerce_readonly USING (true);
CREATE POLICY placements_owner ON commerce.placements FOR ALL TO commerce_owner USING (true) WITH CHECK (true);
GRANT SELECT ON commerce.placements TO commerce_app, commerce_admin, commerce_readonly;

-- ---------------------------------------------------------------- campaigns
-- weight is the campaign's share in the weighted rotation (times the creative's own weight); frequency_cap is how
-- many impressions one visitor may see per frequency_window_seconds. placements are codes of the table above
-- (checked by the service: a CHECK cannot reference another table).
CREATE TABLE commerce.campaigns (
  id                       uuid        PRIMARY KEY,
  name                     text        NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  advertiser               text        NOT NULL CHECK (length(advertiser) BETWEEN 1 AND 120),
  status                   text        NOT NULL CHECK (status IN ('draft', 'active', 'paused', 'ended')),
  valid                    tstzrange   NOT NULL CHECK (NOT isempty(valid) AND NOT lower_inf(valid)),
  weight                   integer     NOT NULL CHECK (weight BETWEEN 1 AND 1000),
  frequency_cap            integer     NOT NULL CHECK (frequency_cap BETWEEN 1 AND 1000),
  frequency_window_seconds integer     NOT NULL CHECK (frequency_window_seconds BETWEEN 60 AND 2592000),
  placements               text[]      NOT NULL CHECK (cardinality(placements) BETWEEN 1 AND 10),
  version                  integer     NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_by               uuid,
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX campaigns_active ON commerce.campaigns USING gin (placements) WHERE status = 'active';
CREATE INDEX campaigns_admin ON commerce.campaigns (created_at DESC, id DESC);
ALTER TABLE commerce.campaigns ENABLE ROW LEVEL SECURITY;
ALTER TABLE commerce.campaigns FORCE  ROW LEVEL SECURITY;
CREATE POLICY campaigns_read ON commerce.campaigns FOR SELECT TO commerce_app, commerce_readonly
  USING (status = 'active');
CREATE POLICY campaigns_operator ON commerce.campaigns FOR ALL TO commerce_admin
  USING ((SELECT commerce.is_operator())) WITH CHECK ((SELECT commerce.is_operator()));
CREATE POLICY campaigns_owner ON commerce.campaigns FOR ALL TO commerce_owner USING (true) WITH CHECK (true);
GRANT SELECT ON commerce.campaigns TO commerce_app, commerce_readonly;
GRANT SELECT, INSERT, UPDATE ON commerce.campaigns TO commerce_admin;

CREATE TABLE commerce.creatives (
  id          uuid        PRIMARY KEY,
  campaign_id uuid        NOT NULL REFERENCES commerce.campaigns (id),
  headline    text        NOT NULL CHECK (length(headline) BETWEEN 1 AND 90),
  body        text        NOT NULL DEFAULT '' CHECK (length(body) <= 300),
  image_url   text        CHECK (image_url IS NULL OR image_url ~ '^https://[^[:space:]]{4,}$' AND length(image_url) <= 2000),
  click_url   text        NOT NULL CHECK (click_url ~ '^https://[^[:space:]]{4,}$' AND length(click_url) <= 2000),
  weight      integer     NOT NULL DEFAULT 1 CHECK (weight BETWEEN 1 AND 1000),
  status      text        NOT NULL CHECK (status IN ('active', 'paused')),
  version     integer     NOT NULL DEFAULT 0 CHECK (version >= 0),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX creatives_campaign ON commerce.creatives (campaign_id, created_at);
CREATE INDEX creatives_campaign_active ON commerce.creatives (campaign_id) WHERE status = 'active';
ALTER TABLE commerce.creatives ENABLE ROW LEVEL SECURITY;
ALTER TABLE commerce.creatives FORCE  ROW LEVEL SECURITY;
CREATE POLICY creatives_read ON commerce.creatives FOR SELECT TO commerce_app, commerce_readonly
  USING (status = 'active');
CREATE POLICY creatives_operator ON commerce.creatives FOR ALL TO commerce_admin
  USING ((SELECT commerce.is_operator())) WITH CHECK ((SELECT commerce.is_operator()));
CREATE POLICY creatives_owner ON commerce.creatives FOR ALL TO commerce_owner USING (true) WITH CHECK (true);
GRANT SELECT ON commerce.creatives TO commerce_app, commerce_readonly;
GRANT SELECT, INSERT, UPDATE ON commerce.creatives TO commerce_admin;

-- ---------------------------------------------------------------- raw impressions (partitioned daily)
-- One row per ad served. visitor_hash is the frequency-cap identity: SHA-256 of the account id when signed in,
-- otherwise of the salted ip + user agent.
CREATE TABLE commerce.ad_impressions_raw (
  id           uuid        NOT NULL,
  created_at   timestamptz NOT NULL,
  creative_id  uuid        NOT NULL,
  campaign_id  uuid        NOT NULL,
  placement    text        NOT NULL,
  account_id   uuid,
  visitor_hash bytea       NOT NULL CHECK (length(visitor_hash) = 32),
  PRIMARY KEY (created_at, id)
) PARTITION BY RANGE (created_at);
ALTER TABLE commerce.ad_impressions_raw ENABLE ROW LEVEL SECURITY;
ALTER TABLE commerce.ad_impressions_raw FORCE  ROW LEVEL SECURITY;
CREATE POLICY ad_impressions_raw_tracker ON commerce.ad_impressions_raw FOR ALL TO commerce_app
  USING ((SELECT commerce.is_tracker())) WITH CHECK ((SELECT commerce.is_tracker()));
CREATE POLICY ad_impressions_raw_own ON commerce.ad_impressions_raw FOR SELECT TO commerce_app
  USING (account_id = (SELECT commerce.app_account()));
CREATE POLICY ad_impressions_raw_owner ON commerce.ad_impressions_raw FOR ALL TO commerce_owner
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON commerce.ad_impressions_raw TO commerce_app;
SELECT commerce.create_partitions_ahead('ad_impressions_raw', 'day', 3);

-- ---------------------------------------------------------------- impression / click counters
CREATE TABLE commerce.ad_counters (
  creative_id uuid        NOT NULL,
  day         date        NOT NULL,
  impressions bigint      NOT NULL DEFAULT 0 CHECK (impressions >= 0),
  clicks      bigint      NOT NULL DEFAULT 0 CHECK (clicks >= 0),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (creative_id, day)
);
ALTER TABLE commerce.ad_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE commerce.ad_counters FORCE  ROW LEVEL SECURITY;
CREATE POLICY ad_counters_tracker ON commerce.ad_counters FOR ALL TO commerce_app
  USING ((SELECT commerce.is_tracker())) WITH CHECK ((SELECT commerce.is_tracker()));
CREATE POLICY ad_counters_operator ON commerce.ad_counters FOR SELECT TO commerce_admin
  USING ((SELECT commerce.is_operator()));
CREATE POLICY ad_counters_owner ON commerce.ad_counters FOR ALL TO commerce_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON commerce.ad_counters TO commerce_app;
GRANT SELECT ON commerce.ad_counters TO commerce_admin;
