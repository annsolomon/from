package in.dexter.portfolio.app;

import in.dexter.contracts.events.aiparser.StatementParsedV1;
import in.dexter.contracts.events.portfolio.StatementSubmittedV1;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.EventEnvelope;
import in.dexter.events.InboxGuard;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.portfolio.app.CardStore.CardRow;
import in.dexter.portfolio.app.StatementStore.LineRow;
import in.dexter.portfolio.app.StatementStore.ParsedRow;
import in.dexter.portfolio.app.StatementStore.UploadRow;
import in.dexter.portfolio.domain.BillingCycle;
import in.dexter.portfolio.domain.PortfolioErrors;
import in.dexter.portfolio.domain.StatementLines;
import in.dexter.portfolio.domain.StatementScan;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Statement uploads (MASTER_PLAN P12): upload → scan → parse → review → confirm.
 * <ol>
 *   <li>{@code POST /v1/statements/uploads} (Idempotency-Key required): an upload row for one of the user's active
 *       cards and a presigned PUT to a quarantine key.</li>
 *   <li>{@code POST /v1/statements/uploads/{id}:submit} (Idempotency-Key required, Mode B, ADR-0017): reads the
 *       quarantined object, scans it ({@link StatementScan}), stores a clean copy, then — in one transaction — marks the
 *       upload submitted and writes {@code portfolio.statement.submitted} with a presigned GET for the ai-parser. A
 *       crash after the clean copy resumes from the recovery point {@code scanned} without scanning again.</li>
 *   <li>The parser's {@code ai-parser.statement.parsed} ({@link #onParsed}, inbox-guarded) stages the header and the
 *       lines, marking lines whose reference a card transaction already has as {@code duplicate}.</li>
 *   <li>{@code :confirm} (Idempotency-Key optional) turns the pending lines into card transactions (source
 *       {@code statement}) through the same write path as a manual record; {@code :discard} drops the upload.</li>
 * </ol>
 * Files are deleted from the object store after the decision (best effort, after commit).
 */
public final class StatementService {
  private static final Logger log = LoggerFactory.getLogger(StatementService.class);
  public static final String PATH = "/v1/statements/uploads";
  public static final String PARSED_CONSUMER = "statement-parsed";
  public static final String CONTENT_TYPE = "application/pdf";
  static final String SCANNED = "scanned";

  /** POST /v1/statements/uploads body. */
  public record NewUpload(UUID userCardId, String fileName, Long sizeBytes) {}

  /** POST {id}:confirm body (optional): line numbers to leave out. */
  public record Confirm(List<Integer> excludeLines) {}

  /** Limits and lifetimes (dexter.portfolio.statements.*). */
  public record Settings(long maxBytes, Duration uploadTtl, Duration fileUrlTtl, Duration submitLease) {}

  /** What one parsed event did (tests and logs). */
  public enum Outcome { APPLIED, FAILED, STALE, DUPLICATE, IGNORED }

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final InboxGuard inbox;
  private final CardStore cards;
  private final StatementStore store;
  private final TransactionService transactions;
  private final StatementFiles files;
  private final OutboxWriter outbox;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final Settings settings;
  private final AtomicBoolean failAfterScan = new AtomicBoolean();
  private final AtomicBoolean failNextCreate = new AtomicBoolean();

  public StatementService(TenantTx tx, IdempotencyEngine idempotency, InboxGuard inbox, CardStore cards,
      StatementStore store, TransactionService transactions, StatementFiles files, OutboxWriter outbox,
      ObjectMapper mapper, Clock clock, Settings settings) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.inbox = inbox;
    this.cards = cards;
    this.store = store;
    this.transactions = transactions;
    this.files = files;
    this.outbox = outbox;
    this.mapper = mapper;
    this.clock = clock;
    this.settings = settings;
  }

  /** Test hook (Mode B recovery): the next submit dies right after the clean copy and the recovery point. */
  public void failAfterScan() {
    failAfterScan.set(true);
  }

  /** Test hook (IdempotencyContract): the next create fails after its business write. */
  public void failNextCreate() {
    failNextCreate.set(true);
  }

  // ------------------------------------------------------------------------------------------------ create

  public IdempotentResponse create(Tenant tenant, UUID key, NewUpload cmd) {
    if (cmd == null) {
      throw new ValidationException(List.of(new FieldError("body", "REQUIRED", "An upload")));
    }
    List<FieldError> e = new ArrayList<>();
    if (cmd.userCardId() == null) {
      e.add(new FieldError("userCardId", "REQUIRED", "The card the statement belongs to"));
    }
    String name = Inputs.text("fileName", cmd.fileName(), 200, true, e);
    if (cmd.sizeBytes() != null && (cmd.sizeBytes() < 1 || cmd.sizeBytes() > settings.maxBytes())) {
      e.add(new FieldError("sizeBytes", "OUT_OF_RANGE", "1-" + settings.maxBytes() + " bytes"));
    }
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, cmd, mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      CardRow card = cards.find(cmd.userCardId()).orElseThrow(() -> new ValidationException(List.of(
          new FieldError("userCardId", "UNKNOWN_CARD", "Not one of your cards"))));
      if (!"active".equals(card.status())) {
        throw new DexterException(PortfolioErrors.CARD_NOT_ACTIVE, "The card is " + card.status());
      }
      UUID id = Ids.newId();
      Instant now = now();
      UploadRow u = new UploadRow(id, tenant.accountId(), card.id(), name, cmd.sizeBytes(),
          "quarantine/" + tenant.accountId() + "/" + id + ".pdf", null, null, "awaiting_upload", null, null, null,
          null, null, null, null, null, now, now, 0);
      store.insert(u);
      StatementFiles.Upload upload = presignPut(u.quarantineKey());
      if (failNextCreate.getAndSet(false)) {
        throw new IllegalStateException("injected failure after the business write");
      }
      return ApiResponse.created(PATH + "/" + id, UploadView.of(u, null, card, upload));
    }));
  }

  private StatementFiles.Upload presignPut(String key) {
    try {
      return files.presignPut(key, settings.uploadTtl());
    } catch (StatementFiles.Unavailable e) {
      throw unavailable(e);
    }
  }

  // ------------------------------------------------------------------------------------------------ submit (Mode B)

  public IdempotentResponse submit(Tenant tenant, UUID id, UUID key) {
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH + "/" + id + ":submit", null, mapper);
    IdempotencyEngine.ExternalStart start = tx.write(tenant,
        () -> idempotency.beginExternal(tenant, key, fingerprint, settings.submitLease()));
    if (start instanceof IdempotencyEngine.Replay r) {
      return r.response();
    }
    String recovery = ((IdempotencyEngine.Proceed) start).recoveryPoint();
    UploadRow u;
    try {
      u = tx.read(tenant, () -> store.find(id)).orElseThrow(() -> new NotFoundException("statement upload"));
      if (!SCANNED.equals(recovery)) {
        if (!"awaiting_upload".equals(u.status())) {
          throw wrongState(u, "submit");
        }
        scanAndCopy(tenant, key, u);
        u = tx.read(tenant, () -> store.find(id)).orElseThrow();
      }
    } catch (RuntimeException ex) {
      abandon(tenant, key);
      throw ex;
    }
    if (failAfterScan.getAndSet(false)) {
      throw new IllegalStateException("injected crash after the scan (recovery point recorded)");
    }
    UploadRow scanned = u;
    IdempotentResponse response;
    try {
      response = publish(tenant, key, id, scanned);
    } catch (StatementFiles.Unavailable ex) {
      abandon(tenant, key);
      throw unavailable(ex);
    } catch (RuntimeException ex) {
      // nothing of phase 2 committed: release the key; the clean copy is rewritten by the retry's scan
      abandon(tenant, key);
      throw ex;
    }
    deleteQuietly(scanned.quarantineKey());
    return response;
  }

  /** Phase 2: presigned GET for the parser, status submitted, the event and the stored response in one transaction. */
  private IdempotentResponse publish(Tenant tenant, UUID key, UUID id, UploadRow scanned) {
    URI fileUrl = files.presignInternalGet(scanned.objectKey(), settings.fileUrlTtl());
    Instant expires = now().plus(settings.fileUrlTtl());
    return tx.write(tenant, () -> {
      UploadRow locked = store.lock(id).orElseThrow(() -> new NotFoundException("statement upload"));
      if (!"awaiting_upload".equals(locked.status())) {
        throw wrongState(locked, "submit");
      }
      CardRow card = cards.find(locked.userCardId()).orElseThrow(() -> new NotFoundException("card"));
      Instant now = now();
      store.markSubmitted(id, now);
      outbox.append(PortfolioEvents.STATEMENT_SUBMITTED, tenant.accountId(), id, 1, new StatementSubmittedV1(id,
          card.id(), card.last4(), fileUrl.toString(), hex(locked.sha256()), locked.sizeBytes(), CONTENT_TYPE,
          expires.toString(), now.toString()));
      UploadRow after = store.find(id).orElseThrow();
      return idempotency.completeExternal(tenant, key, new ApiResponse(202, Map.of(),
          UploadView.of(after, null, card, null)));
    });
  }

  /** Phase 1: read + scan + clean copy, then record the copy and the recovery point in one transaction. */
  private void scanAndCopy(Tenant tenant, UUID key, UploadRow u) {
    byte[] data;
    try {
      data = files.read(u.quarantineKey(), settings.maxBytes());
    } catch (StatementFiles.Missing ex) {
      throw new DexterException(PortfolioErrors.STATEMENT_NOT_UPLOADED,
          "Upload the file to the upload URL first (it may have expired: create a new upload)");
    } catch (StatementFiles.Unavailable ex) {
      throw unavailable(ex);
    }
    StatementScan.Result scan = StatementScan.scan(data, settings.maxBytes());
    if (!scan.ok()) {
      String code = scan.rejection().name();
      tx.write(tenant, () -> {
        store.setStatus(u.id(), "failed", code, rejectionDetail(scan.rejection()), now());
        return null;
      });
      deleteQuietly(u.quarantineKey());
      throw new DexterException(PortfolioErrors.STATEMENT_FILE_REJECTED, rejectionDetail(scan.rejection()), List.of(),
          Map.of("reason", code), null);
    }
    StatementScan.Clean clean = scan.clean();
    UUID other = tx.read(tenant, () -> store.sameFile(u.userCardId(), clean.sha256(), u.id())).orElse(null);
    if (other != null) {
      throw new DexterException(PortfolioErrors.STATEMENT_ALREADY_UPLOADED,
          "This file was already uploaded for this card", List.of(), Map.of("uploadId", other.toString()), null);
    }
    String objectKey = "statements/" + u.accountId() + "/" + u.id() + ".pdf";
    try {
      files.put(objectKey, data, CONTENT_TYPE);
    } catch (StatementFiles.Unavailable ex) {
      throw unavailable(ex);
    }
    tx.write(tenant, () -> {
      store.setScanned(u.id(), objectKey, clean.sha256(), clean.sizeBytes(), now());
      idempotency.recordRecoveryPoint(tenant, key, SCANNED);
      return null;
    });
  }

  private static String rejectionDetail(StatementScan.Rejection r) {
    return switch (r) {
      case EMPTY_FILE -> "The file is empty";
      case FILE_TOO_LARGE -> "The file is larger than the limit";
      case NOT_A_PDF -> "Only PDF statements are accepted";
    };
  }

  private void abandon(Tenant tenant, UUID key) {
    try {
      tx.write(tenant, () -> {
        idempotency.abandonExternal(tenant, key);
        return null;
      });
    } catch (RuntimeException e) {
      log.warn("STATEMENT_SUBMIT_ABANDON_FAILED key={} error={}", key, e.toString());
    }
  }

  // ------------------------------------------------------------------------------------------------ parsed (consumer)

  public Outcome onParsed(EventEnvelope e) {
    if (!StatementParsedV1.TYPE.equals(e.type()) || e.accountId() == null) {
      return Outcome.IGNORED;
    }
    StatementParsedV1 p = mapper.treeToValue(e.data(), StatementParsedV1.class);
    return tx.write(Tenant.user(e.accountId()), () -> {
      if (!inbox.firstDelivery(PARSED_CONSUMER, e.id())) {
        return Outcome.DUPLICATE;
      }
      UploadRow u = store.lock(p.uploadId()).orElse(null);
      if (u == null) {
        return Outcome.IGNORED;
      }
      if (!"submitted".equals(u.status())) {
        return Outcome.STALE;
      }
      Instant now = now();
      if (!"parsed".equals(p.status())) {
        String code = p.errorCode() != null && p.errorCode().matches("[A-Z][A-Z0-9_]{1,63}") ? p.errorCode()
            : "PARSE_FAILED";
        store.setStatus(u.id(), "failed", code, trim(p.errorDetail()), now);
        return Outcome.FAILED;
      }
      List<StatementLines.Line> lines;
      try {
        lines = p.lines() == null ? List.of() : p.lines().stream().map(l -> new StatementLines.Line(l.lineNo(),
            LocalDate.parse(l.postedOn()), l.description(), l.amountPaise(), l.direction(), l.kind())).toList();
      } catch (DateTimeParseException | NullPointerException ex) {
        store.setStatus(u.id(), "failed", "BAD_LINES", "The parser returned unreadable lines", now);
        return Outcome.FAILED;
      }
      String problem = StatementLines.problem(lines, BillingCycle.day(now));
      if (problem != null) {
        store.setStatus(u.id(), "failed", problem, "The parsed lines did not pass validation", now);
        return Outcome.FAILED;
      }
      List<String> refs = StatementLines.refs(u.userCardId(), lines);
      Set<String> existing = store.existingRefs(refs);
      List<LineRow> rows = new ArrayList<>(lines.size());
      long debits = 0;
      long credits = 0;
      for (int i = 0; i < lines.size(); i++) {
        StatementLines.Line l = lines.get(i);
        String ref = refs.get(i);
        rows.add(new LineRow(u.id(), l.lineNo(), u.accountId(), l.postedOn(), l.description().strip(), l.amountPaise(),
            l.direction(), l.kind(), ref, existing.contains(ref) ? "duplicate" : "pending", null));
        if ("debit".equals(l.direction())) {
          debits += l.amountPaise();
        } else {
          credits += l.amountPaise();
        }
      }
      store.insertParsed(new ParsedRow(u.id(), u.accountId(), str(p.parserVersion(), 40, "unknown"),
          str(p.format(), 40, null), str(p.issuer(), 40, null), last4(p.last4()), date(p.periodStart()),
          date(p.periodEnd()), date(p.dueDate()), nonNegative(p.totalDuePaise()), nonNegative(p.minDuePaise()),
          Math.max(0, p.redactions()), lines.size(), debits, credits, existing.size(), now));
      store.insertLines(rows);
      store.markParsed(u.id(), now);
      return Outcome.APPLIED;
    });
  }

  // ------------------------------------------------------------------------------------------------ review

  public UploadView get(Tenant tenant, UUID id) {
    return tx.read(tenant, () -> {
      UploadRow u = store.find(id).orElseThrow(() -> new NotFoundException("statement upload"));
      CardRow card = cards.find(u.userCardId()).orElse(null);
      return UploadView.of(u, store.parsed(id).orElse(null), card, null);
    });
  }

  public Page<UploadView> list(Tenant tenant, UUID cardId, PageRequest page) {
    return tx.read(tenant, () -> store.list(page, cardId).map(u -> UploadView.of(u, null, null, null)));
  }

  public Page<LineView> lines(Tenant tenant, UUID id, PageRequest page) {
    return tx.read(tenant, () -> {
      store.find(id).orElseThrow(() -> new NotFoundException("statement upload"));
      return store.lines(page, id).map(LineView::of);
    });
  }

  // ------------------------------------------------------------------------------------------------ decisions

  /** Imports the pending lines (minus {@code excludeLines}) as card transactions; idempotent per line. */
  public IdempotentResponse confirm(Tenant tenant, UUID id, UUID key, Confirm cmd) {
    Set<Integer> excluded = new HashSet<>(cmd == null || cmd.excludeLines() == null ? List.of() : cmd.excludeLines());
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH + "/" + id + ":confirm", cmd, mapper);
    String[] cleanup = new String[1];
    IdempotentResponse r = tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      UploadRow u = store.lock(id).orElseThrow(() -> new NotFoundException("statement upload"));
      if (!"parsed".equals(u.status())) {
        throw wrongState(u, "confirm");
      }
      CardRow card = cards.lock(u.userCardId()).filter(c -> !c.removed())
          .orElseThrow(() -> new NotFoundException("card"));
      if (!"active".equals(card.status())) {
        throw new DexterException(PortfolioErrors.CARD_NOT_ACTIVE, "The card is " + card.status());
      }
      ParsedRow parsed = store.parsed(id).orElseThrow();
      if (parsed.last4() != null && !parsed.last4().equals(card.last4())) {
        throw new DexterException(PortfolioErrors.STATEMENT_CARD_MISMATCH, "The statement is for the card ending "
            + parsed.last4() + ", not " + card.last4() + "; discard it and upload it for the right card");
      }
      List<LineRow> lines = store.allLines(id);
      Set<String> existing = store.existingRefs(lines.stream().map(LineRow::clientRef).toList());
      Instant now = now();
      int imported = 0;
      int duplicates = 0;
      int skipped = 0;
      for (LineRow l : lines) {
        if (excluded.contains(l.lineNo())) {
          store.setLineStatus(id, l.lineNo(), "excluded", null);
          skipped++;
        } else if (existing.contains(l.clientRef())) {
          store.setLineStatus(id, l.lineNo(), "duplicate", null);
          duplicates++;
        } else {
          TransactionService.Recorded rec = transactions.write(tenant, card, StatementLines.postedAt(l.postedOn()),
              l.amountPaise(), l.kind(), l.description(), null, null, l.clientRef(), "statement", now);
          store.setLineStatus(id, l.lineNo(), "imported", rec.txn().id());
          existing.add(l.clientRef());
          imported++;
        }
      }
      store.markConfirmed(id, imported, duplicates, skipped, now);
      cleanup[0] = u.objectKey();
      return ApiResponse.of(200, UploadView.of(store.find(id).orElseThrow(), parsed, card, null));
    }));
    deleteQuietly(cleanup[0]);
    return r;
  }

  /** Drops an upload that is waiting for its file or was parsed; its files are deleted. */
  public UploadView discard(Tenant tenant, UUID id) {
    UploadView[] out = new UploadView[1];
    UploadRow before = tx.write(tenant, () -> {
      UploadRow u = store.lock(id).orElseThrow(() -> new NotFoundException("statement upload"));
      if ("discarded".equals(u.status())) {
        out[0] = UploadView.of(u, null, null, null);
        return u;
      }
      if (!"awaiting_upload".equals(u.status()) && !"parsed".equals(u.status())) {
        throw wrongState(u, "discard");
      }
      store.setStatus(id, "discarded", null, null, now());
      out[0] = UploadView.of(store.find(id).orElseThrow(), null, null, null);
      return u;
    });
    deleteQuietly(before.quarantineKey());
    deleteQuietly(before.objectKey());
    return out[0];
  }

  // ------------------------------------------------------------------------------------------------ helpers

  private void deleteQuietly(String key) {
    if (key == null) {
      return;
    }
    try {
      files.delete(key);
    } catch (RuntimeException e) {
      log.warn("STATEMENT_FILE_DELETE_FAILED key={} error={}", key, e.toString());
    }
  }

  private static DexterException wrongState(UploadRow u, String action) {
    return new DexterException(PortfolioErrors.STATEMENT_WRONG_STATE, "Cannot " + action + " an upload that is "
        + u.status(), List.of(), Map.of("status", u.status()), null);
  }

  private static DexterException unavailable(RuntimeException e) {
    return new DexterException(PortfolioErrors.OBJECT_STORE_UNAVAILABLE, "The file store is not reachable; retry",
        List.of(), Map.of("retryAfterSeconds", 5), e);
  }

  static String hex(byte[] b) {
    return b == null ? null : java.util.HexFormat.of().formatHex(b);
  }

  private static String trim(String s) {
    return s == null ? null : s.length() <= 200 ? s : s.substring(0, 200);
  }

  private static String str(String s, int max, String fallback) {
    return s == null || s.isBlank() ? fallback : s.length() <= max ? s : s.substring(0, max);
  }

  private static String last4(String s) {
    return s != null && s.matches("[0-9]{4}") ? s : null;
  }

  private static LocalDate date(String s) {
    try {
      return s == null ? null : LocalDate.parse(s);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static Long nonNegative(Long v) {
    return v == null || v < 0 ? null : v;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

}
