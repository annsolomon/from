package in.dexter.catalog.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ingestion data on the catalog_admin pool (MASTER_PLAN P10): sources, crawl runs, snapshots, extractions, the review
 * queue and promotions. RLS lets in editors and the ingestion worker (actor role INGESTOR). Needs the admin TenantTx.
 */
public interface IngestionStore {
  record Source(UUID id, UUID issuerId, String issuerCode, UUID cardProductId, String url, String host, String kind,
      String scheduleCron, boolean enabled, Instant nextRunAt, Instant lastRunAt, Instant createdAt, Instant updatedAt,
      int version) {}

  record Run(UUID id, String trigger, UUID requestedBy, List<UUID> sourceIds, String status, String checkpointJson,
      int attempts, String errorCode, Instant createdAt, Instant startedAt, Instant finishedAt, int version) {}

  record Snapshot(UUID id, UUID sourceId, UUID runId, String url, Instant fetchedAt, byte[] contentHash,
      String contentType, int sizeBytes, String storageKey) {}

  record Extraction(UUID id, UUID snapshotId, UUID cardProductId, String extractedJson, String extractorVersion,
      double confidence) {}

  /** A review item with what the queue shows next to it (card, source URL, fetch time). */
  record ReviewItem(UUID id, UUID extractionId, UUID cardProductId, String cardSlug, String cardName, int baseVersion,
      String diffJson, double confidence, String status, UUID reviewerId, Instant decidedAt, String reason,
      Instant createdAt, int version, UUID sourceId, UUID snapshotId, String url, Instant fetchedAt) {}

  record Promotion(UUID id, UUID reviewItemId, UUID cardProductId, int newVersion, UUID promotedBy,
      Instant promotedAt) {}

  /** A card of an issuer, for matching an extraction by name or URL slug. */
  record CardRef(UUID id, String slug, String name, String status) {}

  // ------------------------------------------------------------------ sources

  void insertSource(Source s);

  Optional<Source> source(UUID id);

  Optional<Source> lockSource(UUID id);

  /** Live (not deleted) sources, newest first. */
  Page<Source> sources(PageRequest page);

  /** True when another live source uses the URL. */
  boolean urlTaken(String url, UUID exceptId);

  /** Writes the mutable fields; false when the stored version is not {@code expectedVersion}. */
  boolean updateSource(Source s, int expectedVersion, Instant now);

  void deleteSource(UUID id, Instant now);

  /** The live, enabled sources among {@code ids} (all live enabled sources when ids is null), at most 500. */
  List<UUID> runnableSources(List<UUID> ids);

  /** Scheduler: due sources, locked (SKIP LOCKED), oldest due first. */
  List<Source> lockDueSources(Instant now, int limit);

  void markScheduled(UUID sourceId, Instant nextRunAt, Instant now);

  void markSourceRun(UUID sourceId, Instant at);

  List<CardRef> issuerCards(UUID issuerId);

  // ------------------------------------------------------------------ runs

  void insertRun(Run r);

  Optional<Run> run(UUID id);

  /** Newest first. */
  Page<Run> runs(PageRequest page);

  /**
   * Claims the oldest queued run, or a running one whose claim is older than {@code staleBefore} (its worker died):
   * status running, attempts + 1, claimed now. Runs that already used {@code maxAttempts} fail
   * ({@code RUN_ABANDONED}) instead.
   */
  Optional<Run> claimRun(Instant now, Instant staleBefore, int maxAttempts);

  void saveCheckpoint(UUID runId, String checkpointJson, Instant now);

  void finishRun(UUID runId, String status, String errorCode, String checkpointJson, Instant now);

  // ------------------------------------------------------------------ snapshots, extractions

  /** The content hash of the latest snapshot of (source, url). */
  Optional<byte[]> latestHash(UUID sourceId, String url);

  void insertSnapshot(Snapshot s);

  void insertExtraction(Extraction e);

  // ------------------------------------------------------------------ review queue

  /** The card's pending item, locked. */
  Optional<ReviewItem> lockPendingFor(UUID cardId);

  void insertReview(UUID id, UUID extractionId, UUID cardId, int baseVersion, String diffJson, double confidence,
      Instant now);

  /** Oldest first, one status. */
  Page<ReviewItem> reviews(PageRequest page, String status);

  Optional<ReviewItem> review(UUID id);

  Optional<ReviewItem> lockReview(UUID id);

  /** pending → approved / rejected (reviewer) or superseded (no reviewer). */
  void decide(UUID id, String status, UUID reviewerId, String reason, Instant now);

  void insertPromotion(Promotion p);

  Optional<Promotion> promotionFor(UUID reviewItemId);
}
