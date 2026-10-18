-- P10 Catalog B (MASTER_PLAN P10): the ingestion pipeline collector -> review -> promoter. Sources (issuer pages,
-- PDFs, sitemaps, images) are fetched by the SSRF-safe collector in crawl runs with resumable checkpoints; a fetch
-- whose content hash differs from the source's latest snapshot is stored, extracted, and diffed field by field
-- against the card's published version; a non-empty diff becomes a review item; an editor's approve runs the
-- promoter (the only writer of published data, the P09 publish path) and records the promotion.
--   catalog_admin  editors (CATALOG_EDITOR / SUPER_ADMIN) everything here; the ingestion worker as actor role
--                  INGESTOR (a system role no token carries) writes runs/snapshots/extractions/review items and only
--                  reads the published catalog.
--   catalog_app    no access (admin-only data).
-- Runs as catalog_owner after V202610171501__catalog_core.
--
-- Query -> index (BUILD_README §4, P10)
--   GET /v1/admin/ingestion/sources (newest first)   (created_at, id) WHERE deleted_at IS NULL                sources_list
--   one live source per URL                          UNIQUE (url) WHERE deleted_at IS NULL                    sources_url_key
--   scheduler: due sources                           (next_run_at) WHERE enabled AND live AND scheduled        sources_due
--   worker claim (oldest queued / stale running)     (created_at) WHERE status IN (queued, running)           crawl_runs_claim
--   GET /v1/admin/ingestion/runs (newest first)      (created_at, id)                                          crawl_runs_list
--   latest snapshot of (source, url)                 (source_id, url, fetched_at DESC)                         snapshots_latest
--   GET /v1/admin/review?status= (oldest first)      (status, created_at, id)                                  review_items_queue
--   the pending item of a card (one at most)         UNIQUE (card_product_id) WHERE status = 'pending'         review_items_card_pending
--   promotion of a review item                       UNIQUE (review_item_id)                                   promotions_review_item_id_key

-- ---------------------------------------------------------------- the ingestion worker's actor role
CREATE FUNCTION catalog.is_ingestor() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS
$$ SELECT coalesce(catalog.app_actor_role() = 'INGESTOR', false) $$;
REVOKE ALL ON FUNCTION catalog.is_ingestor() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION catalog.is_ingestor() TO catalog_admin, catalog_readonly;

-- The worker reads the published catalog to match cards and diff against their current version (read only).
CREATE POLICY issuers_ingestor ON catalog.issuers FOR SELECT TO catalog_admin USING ((SELECT catalog.is_ingestor()));
CREATE POLICY card_products_ingestor ON catalog.card_products FOR SELECT TO catalog_admin
  USING ((SELECT catalog.is_ingestor()));
CREATE POLICY card_product_versions_ingestor ON catalog.card_product_versions FOR SELECT TO catalog_admin
  USING ((SELECT catalog.is_ingestor()));
CREATE POLICY reward_rules_ingestor ON catalog.reward_rules FOR SELECT TO catalog_admin
  USING ((SELECT catalog.is_ingestor()));
CREATE POLICY milestones_ingestor ON catalog.milestones FOR SELECT TO catalog_admin
  USING ((SELECT catalog.is_ingestor()));

-- An editor may discard a published card's draft (it is recreated from the current version on the next read).
GRANT DELETE ON catalog.card_product_drafts TO catalog_admin;

-- ---------------------------------------------------------------- sources
CREATE TABLE catalog.sources (
  id              uuid        PRIMARY KEY,
  issuer_id       uuid        NOT NULL REFERENCES catalog.issuers (id),
  card_product_id uuid        REFERENCES catalog.card_products (id),
  url             text        NOT NULL CHECK (length(url) BETWEEN 10 AND 2048 AND url ~ '^https?://'),
  host            text        NOT NULL CHECK (host = lower(host) AND length(host) BETWEEN 1 AND 260),
  kind            text        NOT NULL CHECK (kind IN ('html', 'pdf', 'sitemap', 'image')),
  schedule_cron   text        CHECK (schedule_cron IS NULL OR length(schedule_cron) BETWEEN 9 AND 120),
  enabled         boolean     NOT NULL DEFAULT true,
  next_run_at     timestamptz,
  last_run_at     timestamptz,
  created_by      uuid,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  deleted_at      timestamptz,
  version         integer     NOT NULL DEFAULT 0,
  -- a sitemap lists many pages; each is matched to a card by name, so the source names none
  CONSTRAINT sources_sitemap_no_card CHECK (kind <> 'sitemap' OR card_product_id IS NULL),
  CONSTRAINT sources_schedule_shape CHECK (schedule_cron IS NOT NULL OR next_run_at IS NULL)
);
CREATE UNIQUE INDEX sources_url_key ON catalog.sources (url) WHERE deleted_at IS NULL;
CREATE INDEX sources_list ON catalog.sources (created_at, id) WHERE deleted_at IS NULL;
CREATE INDEX sources_due ON catalog.sources (next_run_at)
  WHERE enabled AND deleted_at IS NULL AND next_run_at IS NOT NULL;
ALTER TABLE catalog.sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.sources FORCE  ROW LEVEL SECURITY;
CREATE POLICY sources_admin ON catalog.sources FOR ALL TO catalog_admin
  USING ((SELECT catalog.is_editor()) OR (SELECT catalog.is_ingestor()))
  WITH CHECK ((SELECT catalog.is_editor()) OR (SELECT catalog.is_ingestor()));
CREATE POLICY sources_owner ON catalog.sources FOR ALL TO catalog_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON catalog.sources TO catalog_admin;

-- ---------------------------------------------------------------- crawl runs (resumable)
-- checkpoint: {"done": <sources finished>, "stats": {...}, "errors": [...]}; a run re-claimed after a crash
-- continues at source_ids[done + 1].
CREATE TABLE catalog.crawl_runs (
  id           uuid        PRIMARY KEY,
  trigger      text        NOT NULL CHECK (trigger IN ('manual', 'schedule')),
  requested_by uuid,
  source_ids   uuid[]      NOT NULL CHECK (cardinality(source_ids) BETWEEN 1 AND 500),
  status       text        NOT NULL CHECK (status IN ('queued', 'running', 'completed', 'failed')),
  checkpoint   jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(checkpoint) = 'object'),
  attempts     integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  error_code   text        CHECK (error_code IS NULL OR length(error_code) <= 60),
  created_at   timestamptz NOT NULL DEFAULT now(),
  started_at   timestamptz,
  claimed_at   timestamptz,
  finished_at  timestamptz,
  version      integer     NOT NULL DEFAULT 0,
  CONSTRAINT crawl_runs_finished CHECK ((status IN ('completed', 'failed')) = (finished_at IS NOT NULL))
);
CREATE INDEX crawl_runs_claim ON catalog.crawl_runs (created_at) WHERE status IN ('queued', 'running');
CREATE INDEX crawl_runs_list ON catalog.crawl_runs (created_at, id);
ALTER TABLE catalog.crawl_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.crawl_runs FORCE  ROW LEVEL SECURITY;
CREATE POLICY crawl_runs_admin ON catalog.crawl_runs FOR ALL TO catalog_admin
  USING ((SELECT catalog.is_editor()) OR (SELECT catalog.is_ingestor()))
  WITH CHECK ((SELECT catalog.is_editor()) OR (SELECT catalog.is_ingestor()));
CREATE POLICY crawl_runs_owner ON catalog.crawl_runs FOR ALL TO catalog_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON catalog.crawl_runs TO catalog_admin;

-- ---------------------------------------------------------------- snapshots (content in the object store)
-- A snapshot is written only when the content hash differs from the latest one of the same (source, url).
CREATE TABLE catalog.snapshots (
  id           uuid        PRIMARY KEY,
  source_id    uuid        NOT NULL REFERENCES catalog.sources (id),
  run_id       uuid        REFERENCES catalog.crawl_runs (id),
  url          text        NOT NULL CHECK (length(url) BETWEEN 10 AND 2048),
  fetched_at   timestamptz NOT NULL,
  content_hash bytea       NOT NULL CHECK (length(content_hash) = 32),
  content_type text        NOT NULL CHECK (length(content_type) <= 200),
  size_bytes   integer     NOT NULL CHECK (size_bytes >= 0),
  storage_key  text        NOT NULL CHECK (length(storage_key) <= 300)
);
CREATE INDEX snapshots_latest ON catalog.snapshots (source_id, url, fetched_at DESC);
ALTER TABLE catalog.snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.snapshots FORCE  ROW LEVEL SECURITY;
CREATE POLICY snapshots_admin ON catalog.snapshots FOR ALL TO catalog_admin
  USING ((SELECT catalog.is_editor()) OR (SELECT catalog.is_ingestor()))
  WITH CHECK ((SELECT catalog.is_ingestor()));
CREATE POLICY snapshots_owner ON catalog.snapshots FOR ALL TO catalog_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON catalog.snapshots TO catalog_admin;

-- ---------------------------------------------------------------- extractions
CREATE TABLE catalog.extractions (
  id                uuid         PRIMARY KEY,
  snapshot_id       uuid         NOT NULL REFERENCES catalog.snapshots (id),
  card_product_id   uuid         REFERENCES catalog.card_products (id),
  extracted         jsonb        NOT NULL CHECK (jsonb_typeof(extracted) = 'object'),
  extractor_version text         NOT NULL CHECK (length(extractor_version) BETWEEN 1 AND 40),
  confidence        numeric(4,3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
  created_at        timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX extractions_snapshot ON catalog.extractions (snapshot_id);
ALTER TABLE catalog.extractions ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.extractions FORCE  ROW LEVEL SECURITY;
CREATE POLICY extractions_admin ON catalog.extractions FOR ALL TO catalog_admin
  USING ((SELECT catalog.is_editor()) OR (SELECT catalog.is_ingestor()))
  WITH CHECK ((SELECT catalog.is_ingestor()));
CREATE POLICY extractions_owner ON catalog.extractions FOR ALL TO catalog_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON catalog.extractions TO catalog_admin;

-- ---------------------------------------------------------------- review queue
-- diff: {"baseVersion": n, "changes": [{"path", "from", "to", "confidence"}]} against the card's version n. A newer
-- item for the same card supersedes the pending one (at most one pending item per card).
CREATE TABLE catalog.review_items (
  id              uuid         PRIMARY KEY,
  extraction_id   uuid         NOT NULL UNIQUE REFERENCES catalog.extractions (id),
  card_product_id uuid         NOT NULL REFERENCES catalog.card_products (id),
  base_version    integer      NOT NULL CHECK (base_version >= 1),
  diff            jsonb        NOT NULL CHECK (jsonb_typeof(diff) = 'object'),
  confidence      numeric(4,3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
  status          text         NOT NULL CHECK (status IN ('pending', 'approved', 'rejected', 'superseded')),
  reviewer_id     uuid,
  decided_at      timestamptz,
  reason          text         CHECK (reason IS NULL OR length(reason) <= 500),
  created_at      timestamptz  NOT NULL DEFAULT now(),
  version         integer      NOT NULL DEFAULT 0,
  CONSTRAINT review_items_decision CHECK (
    (status = 'pending' AND reviewer_id IS NULL AND decided_at IS NULL)
    OR (status IN ('approved', 'rejected') AND reviewer_id IS NOT NULL AND decided_at IS NOT NULL)
    OR (status = 'superseded' AND reviewer_id IS NULL AND decided_at IS NOT NULL))
);
CREATE INDEX review_items_queue ON catalog.review_items (status, created_at, id);
CREATE UNIQUE INDEX review_items_card_pending ON catalog.review_items (card_product_id) WHERE status = 'pending';
ALTER TABLE catalog.review_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.review_items FORCE  ROW LEVEL SECURITY;
CREATE POLICY review_items_admin ON catalog.review_items FOR ALL TO catalog_admin
  USING ((SELECT catalog.is_editor()) OR (SELECT catalog.is_ingestor()))
  WITH CHECK ((SELECT catalog.is_editor()) OR (SELECT catalog.is_ingestor()));
CREATE POLICY review_items_owner ON catalog.review_items FOR ALL TO catalog_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON catalog.review_items TO catalog_admin;

-- ---------------------------------------------------------------- promotions (one per approved item)
CREATE TABLE catalog.promotions (
  id              uuid        PRIMARY KEY,
  review_item_id  uuid        NOT NULL UNIQUE REFERENCES catalog.review_items (id),
  card_product_id uuid        NOT NULL REFERENCES catalog.card_products (id),
  new_version     integer     NOT NULL CHECK (new_version >= 1),
  promoted_by     uuid        NOT NULL,
  promoted_at     timestamptz NOT NULL
);
ALTER TABLE catalog.promotions ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog.promotions FORCE  ROW LEVEL SECURITY;
CREATE POLICY promotions_admin ON catalog.promotions FOR ALL TO catalog_admin
  USING ((SELECT catalog.is_editor())) WITH CHECK ((SELECT catalog.is_editor()));
CREATE POLICY promotions_owner ON catalog.promotions FOR ALL TO catalog_owner USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON catalog.promotions TO catalog_admin;
