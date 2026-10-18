package in.dexter.catalog.infra;

import in.dexter.catalog.app.IngestionStore;
import in.dexter.db.page.KeyType;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.page.KeysetQuery;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.sql.Rows;
import in.dexter.db.sql.Sql;
import in.dexter.db.tenant.TenantScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Ingestion tables on the catalog_admin pool (RLS: editors and the INGESTOR worker). */
public final class IngestionRepository implements IngestionStore {
  static final String SOURCE_COLS = "s.id, s.issuer_id, i.code AS issuer_code, s.card_product_id, s.url, s.host, s.kind,"
      + " s.schedule_cron, s.enabled, s.next_run_at, s.last_run_at, s.created_at, s.updated_at, s.version";
  static final String SOURCE_FROM = " FROM catalog.sources s JOIN catalog.issuers i ON i.id = s.issuer_id";
  static final RowMapper<Source> SOURCE = (rs, n) -> new Source(Rows.uuid(rs, "id"), Rows.uuid(rs, "issuer_id"),
      rs.getString("issuer_code"), Rows.uuid(rs, "card_product_id"), rs.getString("url"), rs.getString("host"),
      rs.getString("kind"), rs.getString("schedule_cron"), rs.getBoolean("enabled"), Rows.instant(rs, "next_run_at"),
      Rows.instant(rs, "last_run_at"), Rows.instant(rs, "created_at"), Rows.instant(rs, "updated_at"),
      rs.getInt("version"));
  static final String RUN_COLS = "id, trigger, requested_by, source_ids::text[] AS source_ids, status,"
      + " checkpoint::text AS checkpoint, attempts, error_code, created_at, started_at, finished_at, version";
  static final RowMapper<Run> RUN = (rs, n) -> {
    List<UUID> ids = new ArrayList<>();
    for (String s : Rows.textArray(rs, "source_ids")) {
      ids.add(UUID.fromString(s));
    }
    return new Run(Rows.uuid(rs, "id"), rs.getString("trigger"), Rows.uuid(rs, "requested_by"), List.copyOf(ids),
        rs.getString("status"), rs.getString("checkpoint"), rs.getInt("attempts"), rs.getString("error_code"),
        Rows.instant(rs, "created_at"), Rows.instant(rs, "started_at"), Rows.instant(rs, "finished_at"),
        rs.getInt("version"));
  };
  static final String REVIEW_COLS = "r.id, r.extraction_id, r.card_product_id, c.slug AS card_slug,"
      + " c.name AS card_name, r.base_version, r.diff::text AS diff, r.confidence, r.status, r.reviewer_id,"
      + " r.decided_at, r.reason, r.created_at, r.version, sn.source_id, sn.id AS snapshot_id, sn.url, sn.fetched_at";
  static final String REVIEW_FROM = " FROM catalog.review_items r"
      + " JOIN catalog.card_products c ON c.id = r.card_product_id"
      + " JOIN catalog.extractions e ON e.id = r.extraction_id"
      + " JOIN catalog.snapshots sn ON sn.id = e.snapshot_id";
  static final RowMapper<ReviewItem> REVIEW = (rs, n) -> new ReviewItem(Rows.uuid(rs, "id"),
      Rows.uuid(rs, "extraction_id"), Rows.uuid(rs, "card_product_id"), rs.getString("card_slug"),
      rs.getString("card_name"), rs.getInt("base_version"), rs.getString("diff"),
      rs.getBigDecimal("confidence").doubleValue(), rs.getString("status"), Rows.uuid(rs, "reviewer_id"),
      Rows.instant(rs, "decided_at"), rs.getString("reason"), Rows.instant(rs, "created_at"), rs.getInt("version"),
      Rows.uuid(rs, "source_id"), Rows.uuid(rs, "snapshot_id"), rs.getString("url"), Rows.instant(rs, "fetched_at"));
  static final RowMapper<Promotion> PROMOTION = (rs, n) -> new Promotion(Rows.uuid(rs, "id"),
      Rows.uuid(rs, "review_item_id"), Rows.uuid(rs, "card_product_id"), rs.getInt("new_version"),
      Rows.uuid(rs, "promoted_by"), Rows.instant(rs, "promoted_at"));

  /** Sources newest first (sources_list). */
  static final KeysetQuery<Source> SOURCES = KeysetQuery.<Source>builder("created_desc")
      .direction(KeysetQuery.Direction.DESC)
      .key("s.created_at", KeyType.TIMESTAMPTZ)
      .key("s.id", KeyType.UUID)
      .sql("SELECT " + SOURCE_COLS + SOURCE_FROM + " WHERE s.deleted_at IS NULL")
      .mapper(SOURCE)
      .keysOf(s -> List.of(s.createdAt(), s.id()))
      .build();
  /** Runs newest first (crawl_runs_list). */
  static final KeysetQuery<Run> RUNS = KeysetQuery.<Run>builder("created_desc")
      .direction(KeysetQuery.Direction.DESC)
      .key("created_at", KeyType.TIMESTAMPTZ)
      .key("id", KeyType.UUID)
      .sql("SELECT " + RUN_COLS + " FROM catalog.crawl_runs WHERE true")
      .mapper(RUN)
      .keysOf(r -> List.of(r.createdAt(), r.id()))
      .build();
  /** The review queue: one status, oldest first (review_items_queue). */
  static final KeysetQuery<ReviewItem> REVIEWS = KeysetQuery.<ReviewItem>builder("created_asc")
      .direction(KeysetQuery.Direction.ASC)
      .key("r.created_at", KeyType.TIMESTAMPTZ)
      .key("r.id", KeyType.UUID)
      .sql("SELECT " + REVIEW_COLS + REVIEW_FROM + " WHERE r.status = :status")
      .mapper(REVIEW)
      .keysOf(r -> List.of(r.createdAt(), r.id()))
      .build();

  private final JdbcClient jdbc;
  private final KeysetPager pager;

  public IngestionRepository(JdbcClient jdbc, KeysetPager pager) {
    this.jdbc = jdbc;
    this.pager = pager;
  }

  // ------------------------------------------------------------------ sources

  @Override
  public void insertSource(Source s) {
    TenantScope.require();
    jdbc.sql("INSERT INTO catalog.sources (id, issuer_id, card_product_id, url, host, kind, schedule_cron, enabled,"
            + " next_run_at, created_at, updated_at) VALUES (:id, :iss, :card, :url, :host, :kind, :cron, :en, :next,"
            + " :now, :now)")
        .param("id", s.id()).param("iss", s.issuerId()).param("card", s.cardProductId()).param("url", s.url())
        .param("host", s.host()).param("kind", s.kind()).param("cron", s.scheduleCron()).param("en", s.enabled())
        .param("next", s.nextRunAt() == null ? null : Sql.ts(s.nextRunAt())).param("now", Sql.ts(s.createdAt()))
        .update();
  }

  @Override
  public Optional<Source> source(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + SOURCE_COLS + SOURCE_FROM + " WHERE s.id = :id AND s.deleted_at IS NULL")
        .param("id", id).query(SOURCE).optional();
  }

  @Override
  public Optional<Source> lockSource(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + SOURCE_COLS + SOURCE_FROM + " WHERE s.id = :id AND s.deleted_at IS NULL"
        + " FOR UPDATE OF s").param("id", id).query(SOURCE).optional();
  }

  @Override
  public Page<Source> sources(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, SOURCES, page, Map.of(), Map.of());
  }

  @Override
  public boolean urlTaken(String url, UUID exceptId) {
    TenantScope.require();
    return jdbc.sql("SELECT count(1) FROM catalog.sources WHERE url = :url AND deleted_at IS NULL"
            + " AND id IS DISTINCT FROM :except")
        .param("url", url).param("except", exceptId).query(Long.class).single() > 0;
  }

  @Override
  public boolean updateSource(Source s, int expectedVersion, Instant now) {
    TenantScope.require();
    return jdbc.sql("UPDATE catalog.sources SET card_product_id = :card, url = :url, host = :host, kind = :kind,"
            + " schedule_cron = :cron, enabled = :en, next_run_at = :next, updated_at = :now, version = version + 1"
            + " WHERE id = :id AND version = :v AND deleted_at IS NULL")
        .param("card", s.cardProductId()).param("url", s.url()).param("host", s.host()).param("kind", s.kind())
        .param("cron", s.scheduleCron()).param("en", s.enabled())
        .param("next", s.nextRunAt() == null ? null : Sql.ts(s.nextRunAt())).param("now", Sql.ts(now))
        .param("id", s.id()).param("v", expectedVersion)
        .update() == 1;
  }

  @Override
  public void deleteSource(UUID id, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE catalog.sources SET deleted_at = :now, enabled = false, next_run_at = NULL, updated_at = :now,"
        + " version = version + 1 WHERE id = :id AND deleted_at IS NULL").param("now", Sql.ts(now)).param("id", id)
        .update();
  }

  @Override
  public List<UUID> runnableSources(List<UUID> ids) {
    TenantScope.require();
    if (ids == null) {
      return jdbc.sql("SELECT id FROM catalog.sources WHERE deleted_at IS NULL AND enabled ORDER BY created_at, id"
          + " LIMIT 500").query(UUID.class).list();
    }
    List<UUID> found = jdbc.sql("SELECT id FROM catalog.sources WHERE deleted_at IS NULL AND enabled"
        + " AND id = ANY(:ids)").param("ids", Sql.uuids(ids)).query(UUID.class).list();
    // keep the caller's order (the run processes sources in that order)
    List<UUID> out = new ArrayList<>();
    for (UUID id : ids) {
      if (found.contains(id) && !out.contains(id)) {
        out.add(id);
      }
    }
    return out;
  }

  @Override
  public List<Source> lockDueSources(Instant now, int limit) {
    TenantScope.require();
    return jdbc.sql("SELECT " + SOURCE_COLS + SOURCE_FROM + " WHERE s.enabled AND s.deleted_at IS NULL"
            + " AND s.next_run_at IS NOT NULL AND s.next_run_at <= :now ORDER BY s.next_run_at LIMIT :limit"
            + " FOR UPDATE OF s SKIP LOCKED")
        .param("now", Sql.ts(now)).param("limit", limit).query(SOURCE).list();
  }

  @Override
  public void markScheduled(UUID sourceId, Instant nextRunAt, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE catalog.sources SET next_run_at = :next WHERE id = :id")
        .param("next", nextRunAt == null ? null : Sql.ts(nextRunAt)).param("id", sourceId).update();
  }

  @Override
  public void markSourceRun(UUID sourceId, Instant at) {
    TenantScope.require();
    jdbc.sql("UPDATE catalog.sources SET last_run_at = :at WHERE id = :id").param("at", Sql.ts(at))
        .param("id", sourceId).update();
  }

  @Override
  public List<CardRef> issuerCards(UUID issuerId) {
    TenantScope.require();
    return jdbc.sql("SELECT id, slug, name, status FROM catalog.card_products WHERE issuer_id = :iss"
            + " ORDER BY name, id LIMIT 1000")
        .param("iss", issuerId)
        .query((rs, n) -> new CardRef(Rows.uuid(rs, "id"), rs.getString("slug"), rs.getString("name"),
            rs.getString("status")))
        .list();
  }

  // ------------------------------------------------------------------ runs

  @Override
  public void insertRun(Run r) {
    TenantScope.require();
    jdbc.sql("INSERT INTO catalog.crawl_runs (id, trigger, requested_by, source_ids, status, checkpoint, created_at)"
            + " VALUES (:id, :trigger, :by, CAST(:ids AS uuid[]), :status, CAST(:cp AS jsonb), :now)")
        .param("id", r.id()).param("trigger", r.trigger()).param("by", r.requestedBy())
        .param("ids", Sql.uuids(r.sourceIds())).param("status", r.status()).param("cp", r.checkpointJson())
        .param("now", Sql.ts(r.createdAt()))
        .update();
  }

  @Override
  public Optional<Run> run(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + RUN_COLS + " FROM catalog.crawl_runs WHERE id = :id").param("id", id).query(RUN)
        .optional();
  }

  @Override
  public Page<Run> runs(PageRequest page) {
    TenantScope.require();
    return pager.page(jdbc, RUNS, page, Map.of(), Map.of());
  }

  @Override
  public Optional<Run> claimRun(Instant now, Instant staleBefore, int maxAttempts) {
    TenantScope.require();
    // runs that keep dying are given up
    jdbc.sql("UPDATE catalog.crawl_runs SET status = 'failed', error_code = 'RUN_ABANDONED', finished_at = :now,"
            + " version = version + 1 WHERE status = 'running' AND claimed_at < :stale AND attempts >= :max")
        .param("now", Sql.ts(now)).param("stale", Sql.ts(staleBefore)).param("max", maxAttempts).update();
    return jdbc.sql("UPDATE catalog.crawl_runs SET status = 'running', attempts = attempts + 1, claimed_at = :now,"
            + " started_at = coalesce(started_at, :now), version = version + 1"
            + " WHERE id = (SELECT id FROM catalog.crawl_runs WHERE status = 'queued'"
            + " OR (status = 'running' AND claimed_at < :stale AND attempts < :max)"
            + " ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING " + RUN_COLS)
        .param("now", Sql.ts(now)).param("stale", Sql.ts(staleBefore)).param("max", maxAttempts)
        .query(RUN).optional();
  }

  @Override
  public void saveCheckpoint(UUID runId, String checkpointJson, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE catalog.crawl_runs SET checkpoint = CAST(:cp AS jsonb), claimed_at = :now, version = version + 1"
        + " WHERE id = :id").param("cp", checkpointJson).param("now", Sql.ts(now)).param("id", runId).update();
  }

  @Override
  public void finishRun(UUID runId, String status, String errorCode, String checkpointJson, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE catalog.crawl_runs SET status = :status, error_code = :code, checkpoint = CAST(:cp AS jsonb),"
            + " finished_at = :now, version = version + 1 WHERE id = :id")
        .param("status", status).param("code", errorCode).param("cp", checkpointJson).param("now", Sql.ts(now))
        .param("id", runId).update();
  }

  // ------------------------------------------------------------------ snapshots, extractions

  @Override
  public Optional<byte[]> latestHash(UUID sourceId, String url) {
    TenantScope.require();
    return jdbc.sql("SELECT content_hash FROM catalog.snapshots WHERE source_id = :s AND url = :url"
            + " ORDER BY fetched_at DESC LIMIT 1")
        .param("s", sourceId).param("url", url).query((rs, n) -> rs.getBytes(1)).optional();
  }

  @Override
  public void insertSnapshot(Snapshot s) {
    TenantScope.require();
    jdbc.sql("INSERT INTO catalog.snapshots (id, source_id, run_id, url, fetched_at, content_hash, content_type,"
            + " size_bytes, storage_key) VALUES (:id, :s, :run, :url, :at, :hash, :ct, :size, :key)")
        .param("id", s.id()).param("s", s.sourceId()).param("run", s.runId()).param("url", s.url())
        .param("at", Sql.ts(s.fetchedAt())).param("hash", s.contentHash())
        .param("ct", s.contentType() == null ? "application/octet-stream" : truncate(s.contentType(), 200))
        .param("size", s.sizeBytes()).param("key", s.storageKey())
        .update();
  }

  @Override
  public void insertExtraction(Extraction e) {
    TenantScope.require();
    jdbc.sql("INSERT INTO catalog.extractions (id, snapshot_id, card_product_id, extracted, extractor_version,"
            + " confidence) VALUES (:id, :snap, :card, CAST(:x AS jsonb), :ver, :conf)")
        .param("id", e.id()).param("snap", e.snapshotId()).param("card", e.cardProductId())
        .param("x", e.extractedJson()).param("ver", e.extractorVersion()).param("conf", confidence(e.confidence()))
        .update();
  }

  // ------------------------------------------------------------------ review queue

  @Override
  public Optional<ReviewItem> lockPendingFor(UUID cardId) {
    TenantScope.require();
    return jdbc.sql("SELECT " + REVIEW_COLS + REVIEW_FROM + " WHERE r.card_product_id = :c AND r.status = 'pending'"
        + " FOR UPDATE OF r").param("c", cardId).query(REVIEW).optional();
  }

  @Override
  public void insertReview(UUID id, UUID extractionId, UUID cardId, int baseVersion, String diffJson,
      double confidence, Instant now) {
    TenantScope.require();
    jdbc.sql("INSERT INTO catalog.review_items (id, extraction_id, card_product_id, base_version, diff, confidence,"
            + " status, created_at) VALUES (:id, :x, :c, :v, CAST(:diff AS jsonb), :conf, 'pending', :now)")
        .param("id", id).param("x", extractionId).param("c", cardId).param("v", baseVersion).param("diff", diffJson)
        .param("conf", confidence(confidence)).param("now", Sql.ts(now))
        .update();
  }

  @Override
  public Page<ReviewItem> reviews(PageRequest page, String status) {
    TenantScope.require();
    return pager.page(jdbc, REVIEWS, page, Map.of("status", status), Map.of("status", status));
  }

  @Override
  public Optional<ReviewItem> review(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + REVIEW_COLS + REVIEW_FROM + " WHERE r.id = :id").param("id", id).query(REVIEW)
        .optional();
  }

  @Override
  public Optional<ReviewItem> lockReview(UUID id) {
    TenantScope.require();
    return jdbc.sql("SELECT " + REVIEW_COLS + REVIEW_FROM + " WHERE r.id = :id FOR UPDATE OF r").param("id", id)
        .query(REVIEW).optional();
  }

  @Override
  public void decide(UUID id, String status, UUID reviewerId, String reason, Instant now) {
    TenantScope.require();
    jdbc.sql("UPDATE catalog.review_items SET status = :status, reviewer_id = :by, reason = :reason,"
            + " decided_at = :now, version = version + 1 WHERE id = :id AND status = 'pending'")
        .param("status", status).param("by", reviewerId).param("reason", reason).param("now", Sql.ts(now))
        .param("id", id).update();
  }

  @Override
  public void insertPromotion(Promotion p) {
    TenantScope.require();
    jdbc.sql("INSERT INTO catalog.promotions (id, review_item_id, card_product_id, new_version, promoted_by,"
            + " promoted_at) VALUES (:id, :r, :c, :v, :by, :at)")
        .param("id", p.id()).param("r", p.reviewItemId()).param("c", p.cardProductId()).param("v", p.newVersion())
        .param("by", p.promotedBy()).param("at", Sql.ts(p.promotedAt()))
        .update();
  }

  @Override
  public Optional<Promotion> promotionFor(UUID reviewItemId) {
    TenantScope.require();
    return jdbc.sql("SELECT id, review_item_id, card_product_id, new_version, promoted_by, promoted_at"
        + " FROM catalog.promotions WHERE review_item_id = :r").param("r", reviewItemId).query(PROMOTION).optional();
  }

  private static BigDecimal confidence(double c) {
    return BigDecimal.valueOf(Math.max(0, Math.min(1, c))).setScale(3, RoundingMode.HALF_UP);
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}
