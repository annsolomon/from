package in.dexter.ledger.app;

import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.ledger.app.ImportStore.Job;
import in.dexter.ledger.app.ImportStore.RowError;
import in.dexter.ledger.domain.LedgerErrors;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * CSV imports (MASTER_PLAN P08): {@code POST /v1/imports} creates a job and a presigned upload URL; the client PUTs
 * the file to the object store and calls {@code POST /v1/imports/{id}:start}; the worker ({@link ImportProcessor})
 * stages, validates and merges it asynchronously; {@code GET /v1/imports/{id}} shows progress, counts and the row
 * errors.
 */
public final class ImportService {
  public static final String PATH = "/v1/imports";
  public static final Duration UPLOAD_TTL = Duration.ofMinutes(15);
  public static final int ERRORS_SHOWN = 100;

  /** POST /v1/imports body. */
  public record NewImport(String fileName, Long sizeBytes) {}

  public record ImportView(UUID id, String status, String fileName, int total, int ok, int duplicates, int failed,
      String errorCode, String errorDetail, String createdAt, String startedAt, String finishedAt, long version,
      ImportFiles.Upload upload, String csvHeader, List<RowError> errors) {

    static ImportView of(Job j, ImportFiles.Upload upload, List<RowError> errors, String header) {
      return new ImportView(j.id(), j.status(), j.fileName(), j.total(), j.ok(), j.duplicates(), j.failed(),
          j.errorCode(), j.errorDetail(), str(j.createdAt()), str(j.startedAt()), str(j.finishedAt()), j.version(),
          upload, header, errors);
    }

    private static String str(Instant t) {
      return t == null ? null : t.toString();
    }
  }

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final ImportStore store;
  private final ImportFiles files;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final long maxBytes;
  private final String header;

  public ImportService(TenantTx tx, IdempotencyEngine idempotency, ImportStore store, ImportFiles files,
      ObjectMapper mapper, Clock clock, long maxBytes, String header) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.store = store;
    this.files = files;
    this.mapper = mapper;
    this.clock = clock;
    this.maxBytes = maxBytes;
    this.header = header;
  }

  /** Idempotency-Key optional (a retried create without a key makes a second, unused job). */
  public IdempotentResponse create(Tenant tenant, UUID key, NewImport cmd) {
    List<FieldError> errors = new ArrayList<>();
    String name = cmd == null || cmd.fileName() == null ? null : cmd.fileName().strip();
    if (name == null || name.isEmpty() || name.length() > 200) {
      errors.add(new FieldError("fileName", name == null ? "REQUIRED" : "INVALID", "1-200 characters"));
    }
    if (cmd != null && cmd.sizeBytes() != null && (cmd.sizeBytes() < 1 || cmd.sizeBytes() > maxBytes)) {
      errors.add(new FieldError("sizeBytes", "OUT_OF_RANGE", "1-" + maxBytes + " bytes"));
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, cmd, mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      UUID id = Ids.newId();
      Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
      String fileKey = "imports/" + tenant.accountId() + "/" + id + ".csv";
      Job job = new Job(id, tenant.accountId(), "awaiting_upload", fileKey, name, 0, 0, 0, 0, null, null, 0, now,
          null, null, 0);
      store.insert(job);
      ImportFiles.Upload upload = files.presignPut(fileKey, UPLOAD_TTL);
      return ApiResponse.created(PATH + "/" + id, ImportView.of(job, upload, List.of(), header));
    }));
  }

  /**
   * Queues an uploaded file. Idempotent while the job is queued or running; a finished job cannot be started again
   * (import the file again: rows already imported are skipped, so that is safe).
   */
  public ImportView start(Tenant tenant, UUID id) {
    return tx.write(tenant, () -> {
      Job j = store.lock(id).orElseThrow(() -> new NotFoundException("import"));
      switch (j.status()) {
        case "awaiting_upload" -> store.setStatus(id, "queued");
        case "queued", "running" -> {
          // already on its way
        }
        default -> throw new DexterException(LedgerErrors.IMPORT_NOT_STARTABLE,
            "The import is " + j.status() + "; create a new import to load the file again");
      }
      Job now = store.find(id).orElseThrow();
      return ImportView.of(now, null, List.of(), header);
    });
  }

  public ImportView get(Tenant tenant, UUID id) {
    return tx.read(tenant, () -> {
      Job j = store.find(id).orElseThrow(() -> new NotFoundException("import"));
      return ImportView.of(j, null, store.errors(id, ERRORS_SHOWN), header);
    });
  }
}
