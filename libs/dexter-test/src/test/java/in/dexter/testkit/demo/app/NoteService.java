package in.dexter.testkit.demo.app;

import in.dexter.cache.CacheInvalidator;
import in.dexter.contracts.events.demo.NoteCreatedV1;
import in.dexter.contracts.events.demo.NoteRenamedV1;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.testkit.demo.DemoEvents;
import in.dexter.testkit.demo.config.CacheScopes;
import in.dexter.testkit.demo.domain.Note;
import in.dexter.testkit.demo.domain.NoteRules;
import in.dexter.testkit.demo.infra.NoteRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;

/**
 * Use-cases (app): the transaction boundary lives here. {@code create} shows the full P02 pattern —
 * validate outside the transaction, then one {@code TenantTx.write} holding the idempotency key, the
 * business row, the outbox event and the stored response. Every write that changes notes also registers the
 * cache bump for after the commit (Path A, ADR-0004); the outbox event drives Path B.
 */
public final class NoteService {
  public static final String PATH = "/demo/notes";

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final OutboxWriter outbox;
  private final NoteRepository repo;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final CacheInvalidator cache;
  /** Test seam for IdempotencyContract case 4: fail after the business write, inside the transaction. */
  private final AtomicBoolean failNext = new AtomicBoolean();

  public NoteService(TenantTx tx, IdempotencyEngine idempotency, OutboxWriter outbox, NoteRepository repo,
      ObjectMapper mapper, Clock clock, CacheInvalidator cache) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.repo = repo;
    this.mapper = mapper;
    this.clock = clock;
    this.cache = cache;
  }

  public void failNextCreate() {
    failNext.set(true);
  }

  public IdempotentResponse create(Tenant t, UUID key, CreateNote cmd) {
    NoteRules.validate(cmd.title(), cmd.kind(), cmd.amountPaise());
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, cmd, mapper);
    return tx.write(t, () -> idempotency.execute(t, key, fingerprint, () -> {
      Note n = repo.insert(t.accountId(), Ids.newId(), cmd.title(), cmd.kind(), cmd.amountPaise(),
          clock.instant().truncatedTo(ChronoUnit.MICROS));
      outbox.append(DemoEvents.NOTE_CREATED, t.accountId(), n.id(), n.version(),
          new NoteCreatedV1(n.id(), n.title(), n.amountPaise(), n.createdAt().toString()));
      cache.afterCommit(t.accountId(), CacheScopes.NOTES);
      if (failNext.getAndSet(false)) {
        throw new IllegalStateException("injected failure after the business write");
      }
      return ApiResponse.created(PATH + "/" + n.id(), NoteView.of(n));
    }));
  }

  /** Versioned rename; emits demo.note.renamed with the new aggregate version. */
  public long rename(Tenant t, UUID noteId, long expectedVersion, String title) {
    NoteRules.validate(title, "note", 0);
    return tx.write(t, () -> {
      long v = repo.rename(t.accountId(), noteId, expectedVersion, title);
      outbox.append(DemoEvents.NOTE_RENAMED, t.accountId(), noteId, v, new NoteRenamedV1(noteId, title));
      cache.afterCommit(t.accountId(), CacheScopes.NOTES);
      return v;
    });
  }

  /** Uncached list (the cached read path is {@link NoteQueries}). */
  public Page<NoteView> list(Tenant t, PageRequest req, String kind) {
    if (kind != null) {
      NoteRules.validate("x", kind, 0);
    }
    return tx.read(t, () -> repo.list(req, kind).map(NoteView::of));
  }
}
