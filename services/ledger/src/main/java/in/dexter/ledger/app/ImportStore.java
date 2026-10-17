package in.dexter.ledger.app;

import java.io.InputStream;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code ledger.import_jobs} + {@code import_errors} (tenant tables) and the CSV staging (P08). */
public interface ImportStore {
  record Job(UUID id, UUID accountId, String status, String fileKey, String fileName, int total, int ok,
      int duplicates, int failed, String errorCode, String errorDetail, int attempts, Instant createdAt,
      Instant startedAt, Instant finishedAt, long version) {}

  record RowError(int rowNo, String code, String detail) {}

  /** A job the worker claimed (status now {@code running}). */
  record Claim(UUID jobId, UUID accountId, String fileKey, int attempts) {}

  /** One staged CSV row (text as in the file; {@code rowNo} 1 = first data row). */
  record StagedRow(int rowNo, String date, String amount, String merchant, String category, String paymentMethod,
      String note, String clientRef) {}

  /** The file is not a valid import CSV (bad header, wrong column count, encoding, too large, too many rows). */
  final class InvalidFile extends RuntimeException {
    private final String code;

    public InvalidFile(String code, String detail) {
      super(detail);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  void insert(Job job);

  Optional<Job> find(UUID id);

  Optional<Job> lock(UUID id);

  /** Sets status (+ version, updated_at). */
  void setStatus(UUID id, String status);

  /** Final state of a job: counts, status, error. */
  void finish(UUID id, String status, int total, int ok, int duplicates, int failed, String errorCode,
      String errorDetail, Instant finishedAt);

  void insertErrors(UUID jobId, UUID accountId, List<RowError> errors);

  List<RowError> errors(UUID jobId, int limit);

  /** The oldest claimable job of any account (no tenant context: SECURITY DEFINER function). */
  Optional<Claim> claim(Duration stale, int maxAttempts);

  /**
   * COPYs the CSV into a transaction-scoped temporary table and reads it back (needs a write TenantTx). Throws
   * {@link InvalidFile} for a malformed file.
   */
  List<StagedRow> stage(InputStream csv, long maxBytes, int maxRows);
}
