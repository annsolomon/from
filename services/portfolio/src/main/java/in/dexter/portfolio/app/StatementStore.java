package in.dexter.portfolio.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Statement uploads, the parser's staged results and their lines (portfolio_app pool, RLS per account). */
public interface StatementStore {
  record UploadRow(UUID id, UUID accountId, UUID userCardId, String fileName, Long sizeBytes, String quarantineKey,
      String objectKey, byte[] sha256, String status, String errorCode, String errorDetail, Integer importedCount,
      Integer duplicateCount, Integer excludedCount, Instant submittedAt, Instant parsedAt, Instant decidedAt,
      Instant createdAt, Instant updatedAt, int version) {}

  record ParsedRow(UUID uploadId, UUID accountId, String parserVersion, String format, String issuer, String last4,
      LocalDate periodStart, LocalDate periodEnd, LocalDate dueDate, Long totalDuePaise, Long minDuePaise,
      int redactions, int lineCount, long debitsPaise, long creditsPaise, int duplicateCount, Instant parsedAt) {}

  record LineRow(UUID uploadId, int lineNo, UUID accountId, LocalDate postedOn, String description, long amountPaise,
      String direction, String kind, String clientRef, String status, UUID cardTxnId) {}

  void insert(UploadRow u);

  Optional<UploadRow> find(UUID id);

  Optional<UploadRow> lock(UUID id);

  /** The account's uploads newest first, optionally of one card. */
  Page<UploadRow> list(PageRequest page, UUID cardId);

  /** Another live (submitted, parsed or confirmed) upload of the same file for the card. */
  Optional<UUID> sameFile(UUID cardId, byte[] sha256, UUID exceptId);

  /** After the scan: the clean copy's key, hash and size (status unchanged). */
  void setScanned(UUID id, String objectKey, byte[] sha256, long sizeBytes, Instant now);

  void setStatus(UUID id, String status, String errorCode, String errorDetail, Instant now);

  void markSubmitted(UUID id, Instant now);

  void markParsed(UUID id, Instant now);

  void markConfirmed(UUID id, int imported, int duplicates, int excluded, Instant now);

  void insertParsed(ParsedRow p);

  Optional<ParsedRow> parsed(UUID uploadId);

  /** Multi-row inserts. */
  void insertLines(List<LineRow> lines);

  /** One upload's lines in order. */
  Page<LineRow> lines(PageRequest page, UUID uploadId);

  /** Every line of the upload in order (confirm). */
  List<LineRow> allLines(UUID uploadId);

  void setLineStatus(UUID uploadId, int lineNo, String status, UUID cardTxnId);

  /** Which of these card-transaction client references the account already has. */
  Set<String> existingRefs(Collection<String> refs);
}
