package in.dexter.audit.app;

import in.dexter.audit.domain.ChainVerifier;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The audit log: the single writer, the admin's keyset read, and what the chain walk needs. */
public interface AuditStore {
  /** One entry to append. {@code details} is a JSON object (never personal data beyond ids). */
  record Entry(String actorType, @Nullable String actorId, String action, @Nullable String targetType,
      @Nullable String targetId, byte @Nullable [] ipHash, @Nullable String requestId, Map<String, Object> details) {}

  /** A stored row as the admin reads it. */
  record Row(long seq, Instant occurredAt, UUID id, String actorType, @Nullable String actorId, String action,
      @Nullable String targetType, @Nullable String targetId, @Nullable String requestId, String detailsJson,
      byte[] hash) {}

  /** The admin list's filters (all optional, combined with AND). */
  record Filter(@Nullable String actorId, @Nullable String action, @Nullable String targetType,
      @Nullable String targetId, @Nullable Instant from, @Nullable Instant to) {}

  /** The chain head: last seq and its hash. */
  record Head(long seq, byte[] hash) {}

  /** Appends through {@code audit.append} (the advisory lock, the chain head); returns the new seq. */
  long append(Entry e);

  Page<Row> page(Filter f, PageRequest page);

  /** Rows with seq >= {@code fromSeq} in seq order, with the hash the database recomputes from each row. */
  List<ChainVerifier.Row> chain(long fromSeq, int limit);

  Head head();

  /** Every anchor, by seq. */
  Map<Long, byte[]> anchors();

  long lastAnchorSeq();

  void insertAnchor(long seq, byte[] hash, String objectKey);
}
