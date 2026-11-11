package in.dexter.audit.app;

import in.dexter.audit.app.AuditStore.Entry;
import in.dexter.audit.app.AuditViews.AdminPrivacyView;
import in.dexter.audit.app.AuditViews.PrivacyView;
import in.dexter.audit.app.PrivacyStore.Request;
import in.dexter.audit.domain.AuditErrors;
import in.dexter.audit.domain.NotificationRules;
import in.dexter.audit.domain.PrivacySaga;
import in.dexter.audit.domain.SubjectRef;
import in.dexter.contracts.events.audit.PrivacyRequestedV1;
import in.dexter.contracts.events.privacy.PrivacyCompletedV1;
import in.dexter.core.error.DexterException;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The privacy saga's orchestrator (MASTER_PLAN P18, ADR-0024 §3). A user asks for an export or a deletion
 * (step-up, Idempotency-Key); the request row is the saga's whole state, so it resumes from the database after any
 * restart. A remote phase is asked with {@code audit.privacy.requested} (outbox, same transaction as the state
 * change) and waits for every service's {@code <svc>.privacy.completed}; the job re-asks the services that have not
 * answered when the phase times out, up to {@code maxAttempts}, then fails the request (an operator can resume it).
 * Local phases run here: a deletion's last step erases audit's own rows and leaves a pseudonymous record; an
 * export's zips every part with audit's own into one archive, downloadable for 24 hours through a presigned link.
 */
public final class PrivacyService {
  private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);
  public static final String PATH = "/v1/privacy";
  public static final String ADMIN_PATH = "/v1/admin/audit/privacy-requests";
  public static final String ACK_CONSUMER = "privacy-acks";
  public static final Duration DOWNLOAD_TTL = Duration.ofHours(24);

  /** The saga's settings. */
  public record Settings(List<String> services, Duration phaseTimeout, int maxAttempts) {
    public Settings {
      services = List.copyOf(services);
    }
  }

  private final TenantTx tx;
  private final PrivacyStore store;
  private final AuditStore audit;
  private final NotificationService notifications;
  private final IdempotencyEngine idempotency;
  private final OutboxWriter outbox;
  private final InboxGuard inbox;
  private final ExportStore exports;
  private final SubjectRef subjects;
  private final JsonMapper mapper;
  private final Clock clock;
  private final Settings settings;
  private final TenantTx adminTx;
  private final PrivacyStore adminStore;

  public PrivacyService(TenantTx tx, PrivacyStore store, AuditStore audit, NotificationService notifications,
      IdempotencyEngine idempotency, OutboxWriter outbox, InboxGuard inbox, ExportStore exports, SubjectRef subjects,
      JsonMapper mapper, Clock clock, Settings settings, TenantTx adminTx, PrivacyStore adminStore) {
    this.tx = tx;
    this.store = store;
    this.audit = audit;
    this.notifications = notifications;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.inbox = inbox;
    this.exports = exports;
    this.subjects = subjects;
    this.mapper = mapper;
    this.clock = clock;
    this.settings = settings;
    this.adminTx = adminTx;
    this.adminStore = adminStore;
  }

  // ------------------------------------------------------------------------------------------- user requests

  /** POST /v1/privacy/export or /delete: 202 with the request; 409 when one of this kind is already running. */
  public IdempotentResponse request(Tenant tenant, UUID key, String kind) {
    String path = PATH + "/" + kind;
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", path, Map.of("kind", kind), mapper);
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint, () -> {
      Instant now = clock.instant();
      String phase = PrivacySaga.firstActionable(kind, PrivacySaga.firstPhase(kind), settings.services());
      boolean remote = PrivacySaga.remote(phase);
      Request r = new Request(Ids.newId(), tenant.accountId(), null, kind, "running", phase, 1, settings.services(),
          "{}", now, remote ? now.plus(settings.phaseTimeout()) : now, null, null, null, now, null, 0);
      if (!store.insert(r)) {
        throw new DexterException(AuditErrors.PRIVACY_REQUEST_RUNNING,
            "A " + kind + " request is already running for this account");
      }
      audit.append(new Entry("user", tenant.accountId().toString(), "privacy." + kind + ".requested",
          "privacy_request", r.id().toString(), null, MDC.get("requestId"), Map.of()));
      if (remote) {
        ask(r, PrivacySaga.servicesFor(phase, r.services()), now);
      }
      Request started = r;
      return new ApiResponse(202, Map.of("Location", PATH + "/requests/" + r.id()), view(started));
    }));
  }

  public PrivacyView get(Tenant tenant, UUID id) {
    return tx.read(tenant, () -> view(store.find(id).orElseThrow(PrivacyService::notFound)));
  }

  public Page<PrivacyView> list(Tenant tenant, PageRequest page) {
    return tx.read(tenant, () -> store.list(page).map(this::view));
  }

  public AdminPrivacyView adminGet(Tenant operator, UUID id) {
    return adminTx.read(operator, () -> adminView(adminStore.find(id).orElseThrow(PrivacyService::notFound)));
  }

  /**
   * An operator restarts a failed request at its current phase, attempt 1, asking only the services that have not
   * answered it (SYSTEM does the work; the operator is audited).
   */
  public AdminPrivacyView resume(Tenant operator, String operatorId, UUID id) {
    adminTx.read(operator, () -> adminStore.find(id).orElseThrow(PrivacyService::notFound));
    Request after = tx.write(AuditTenants.SYSTEM, () -> {
      Request r = store.lock(id).orElseThrow(PrivacyService::notFound);
      if (!"failed".equals(r.status())) {
        throw new DexterException(AuditErrors.PRIVACY_REQUEST_NOT_FAILED, "This request is " + r.status());
      }
      audit.append(new Entry("admin", operatorId, "privacy." + r.kind() + ".resumed", "privacy_request",
          r.id().toString(), null, MDC.get("requestId"), Map.of("phase", r.phase())));
      Instant now = clock.instant();
      boolean remote = PrivacySaga.remote(r.phase());
      Request running = save(with(r, "running", r.phase(), 1, r.stepsJson(), now,
          remote ? now.plus(settings.phaseTimeout()) : now, r.archiveKey(), r.archiveBytes(), null, null));
      List<String> waiting = remote ? pending(running) : List.of();
      if (!waiting.isEmpty()) {
        ask(running, waiting, now);
      }
      return running;
    });
    return adminView(after);
  }

  // ------------------------------------------------------------------------------------------------ the saga

  /**
   * Starts {@code phase} (attempt 1): asks its services and sets the timeout, or — for a local phase, or a remote
   * one nobody runs here — makes the request due now for the job.
   */
  private Request enter(Request r, String wanted, Instant now) {
    String phase = PrivacySaga.firstActionable(r.kind(), wanted, r.services());
    List<String> asked = PrivacySaga.remote(phase) ? PrivacySaga.servicesFor(phase, r.services()) : List.of();
    Request next = with(r, "running", phase, 1, r.stepsJson(), now,
        asked.isEmpty() ? now : now.plus(settings.phaseTimeout()), r.archiveKey(), r.archiveBytes(), null, null);
    next = save(next);
    if (!asked.isEmpty()) {
      ask(next, asked, now);
    }
    return next;
  }

  private void ask(Request r, List<String> services, Instant now) {
    outbox.append(AuditEvents.PRIVACY_REQUESTED, r.accountId(), r.id(), r.attempt(), new PrivacyRequestedV1(r.id(),
        r.kind(), r.phase(), r.attempt(), services, now.toString()));
    log.info("PRIVACY_PHASE_ASKED request={} phase={} attempt={} services={}", r.id(), r.phase(), r.attempt(),
        services);
  }

  /** The acknowledgements' consumer (one transaction per event, inbox-deduplicated). */
  public void onAck(EventEnvelope event) {
    if (!event.type().endsWith(".privacy.completed")) {
      return;
    }
    PrivacyCompletedV1 ack = mapper.treeToValue(event.data(), PrivacyCompletedV1.class);
    tx.writeVoid(AuditTenants.SYSTEM, () -> {
      if (!inbox.firstDelivery(ACK_CONSUMER, event.id())) {
        return;
      }
      Request r = store.lock(ack.requestId()).orElse(null);
      if (r == null || !"running".equals(r.status()) || !r.phase().equals(ack.phase())) {
        log.info("PRIVACY_ACK_IGNORED request={} phase={} service={}", ack.requestId(), ack.phase(), ack.service());
        return;
      }
      ObjectNode steps = steps(r);
      ObjectNode phase = steps.has(ack.phase()) ? (ObjectNode) steps.get(ack.phase()) : steps.putObject(ack.phase());
      ObjectNode s = phase.putObject(ack.service());
      s.put("rows", ack.rows());
      s.put("remaining", ack.remaining());
      s.put("attempt", ack.attempt());
      s.put("done", PrivacySaga.done(ack.phase(), ack.service(), ack.remaining()));
      if (ack.partKey() != null) {
        s.put("partKey", ack.partKey());
      }
      s.put("at", ack.completedAt());
      Instant now = clock.instant();
      Request updated = with(r, r.status(), r.phase(), r.attempt(), steps.toString(), r.phaseStartedAt(),
          r.nextCheckAt(), r.archiveKey(), r.archiveBytes(), r.error(), null);
      if (pending(updated).isEmpty()) {
        enter(updated, PrivacySaga.next(r.kind(), r.phase()), now);
      } else {
        save(updated);
      }
    });
  }

  /** The services of the current phase that have not answered it (successfully). */
  List<String> pending(Request r) {
    JsonNode phase = steps(r).get(r.phase());
    List<String> out = new ArrayList<>();
    for (String svc : PrivacySaga.servicesFor(r.phase(), r.services())) {
      JsonNode s = phase == null ? null : phase.get(svc);
      if (s == null || !s.path("done").isBoolean() || !s.path("done").booleanValue()) {
        out.add(svc);
      }
    }
    return out;
  }

  /** The job: every due request, each in its own transaction. Returns how many were handled. */
  public int tick(int limit) {
    Instant now = clock.instant();
    List<UUID> due = tx.write(AuditTenants.SYSTEM, () -> store.due(now, limit));
    int n = 0;
    for (UUID id : due) {
      try {
        tx.writeVoid(AuditTenants.SYSTEM, () -> advance(id));
        n++;
      } catch (RuntimeException e) {
        log.warn("PRIVACY_TICK_FAILED request={} error={}", id, e.toString());
        tx.writeVoid(AuditTenants.SYSTEM, () -> store.lock(id).ifPresent(r -> retryOrFail(r, e.toString())));
      }
    }
    return n;
  }

  private void advance(UUID id) {
    Request r = store.lock(id).orElse(null);
    Instant now = clock.instant();
    if (r == null || !"running".equals(r.status()) || r.nextCheckAt() == null || r.nextCheckAt().isAfter(now)) {
      return;
    }
    if (PrivacySaga.remote(r.phase())) {
      List<String> waiting = pending(r);
      if (waiting.isEmpty()) {
        enter(r, PrivacySaga.next(r.kind(), r.phase()), now);
        return;
      }
      if (r.attempt() >= settings.maxAttempts()) {
        fail(r, "no answer to " + r.phase() + " from " + String.join(", ", waiting) + " after " + r.attempt()
            + " attempts");
        return;
      }
      Request again = save(with(r, "running", r.phase(), r.attempt() + 1, r.stepsJson(), r.phaseStartedAt(),
          now.plus(settings.phaseTimeout()), r.archiveKey(), r.archiveBytes(), null, null));
      ask(again, waiting, now);
      return;
    }
    switch (r.phase()) {
      case PrivacySaga.LOCAL -> finishDeletion(r, now);
      case PrivacySaga.ARCHIVE -> finishExport(r, now);
      default -> throw new IllegalStateException("unexpected phase " + r.phase());
    }
  }

  private void retryOrFail(Request r, String error) {
    if (!"running".equals(r.status())) {
      return;
    }
    Instant now = clock.instant();
    if (r.attempt() >= settings.maxAttempts()) {
      fail(r, error);
    } else {
      save(with(r, "running", r.phase(), r.attempt() + 1, r.stepsJson(), r.phaseStartedAt(),
          now.plus(settings.phaseTimeout()), r.archiveKey(), r.archiveBytes(), trim(error), null));
    }
  }

  private void fail(Request r, String error) {
    save(with(r, "failed", r.phase(), r.attempt(), r.stepsJson(), r.phaseStartedAt(), null, r.archiveKey(),
        r.archiveBytes(), trim(error), null));
    audit.append(new Entry("system", null, "privacy." + r.kind() + ".failed", "privacy_request", r.id().toString(),
        null, null, Map.of("phase", r.phase(), "attempt", r.attempt())));
    log.warn("PRIVACY_REQUEST_FAILED request={} phase={} error={}", r.id(), r.phase(), error);
  }

  /** A deletion's last step: audit's own rows go, and the account's requests keep only the pseudonym. */
  private void finishDeletion(Request r, Instant now) {
    UUID account = r.accountId();
    if (account == null) {
      throw new IllegalStateException("deletion without an account");
    }
    long erased = store.eraseLocal(account);
    String subject = subjects.of(account);
    ObjectNode steps = steps(r);
    ObjectNode local = steps.putObject(PrivacySaga.LOCAL).putObject("audit");
    local.put("rows", erased);
    local.put("remaining", 0);
    local.put("done", true);
    local.put("at", now.toString());
    save(with(r, "completed", PrivacySaga.DONE, r.attempt(), steps.toString(), now, null, null, null, null, now));
    store.pseudonymize(account, subject);
    long remaining = store.remainingLocal(account);
    if (remaining != 0) {
      throw new IllegalStateException("audit still holds " + remaining + " rows of the account");
    }
    audit.append(new Entry("system", null, "privacy.delete.completed", "subject", subject, null, null,
        Map.of("request", r.id().toString(), "auditRows", erased)));
    log.info("PRIVACY_DELETE_COMPLETED request={}", r.id());
  }

  /** An export's last step: every service's part and audit's own, zipped into one archive. */
  private void finishExport(Request r, Instant now) {
    UUID account = r.accountId();
    if (account == null) {
      throw new IllegalStateException("export without an account");
    }
    JsonNode parts = steps(r).path(PrivacySaga.EXPORT);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      Map<String, Object> manifest = new LinkedHashMap<>();
      manifest.put("request", r.id().toString());
      manifest.put("account", account.toString());
      manifest.put("createdAt", r.createdAt().toString());
      manifest.put("completedAt", now.toString());
      List<String> files = new ArrayList<>();
      for (String svc : PrivacySaga.servicesFor(PrivacySaga.EXPORT, r.services())) {
        JsonNode part = parts.path(svc).path("partKey");
        String key = part.isString() ? part.asString() : "";
        if (key.isEmpty()) {
          throw new IllegalStateException("no export part from " + svc);
        }
        add(zip, svc + ".json", exports.get(key));
        files.add(svc + ".json");
      }
      add(zip, "audit.json", store.exportLocal(account).getBytes(StandardCharsets.UTF_8));
      files.add("audit.json");
      manifest.put("files", files);
      add(zip, "manifest.json", mapper.writeValueAsBytes(manifest));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    byte[] archive = bytes.toByteArray();
    String key = "privacy/" + r.id() + "/export.zip";
    exports.put(key, archive, "application/zip");
    save(with(r, "completed", PrivacySaga.DONE, r.attempt(), r.stepsJson(), now, null, key, (long) archive.length,
        null, now));
    notifications.notify(account, NotificationRules.PRIVACY, "privacy.export-ready",
        Map.of("request", r.id().toString()), false, r.id());
    audit.append(new Entry("system", null, "privacy.export.completed", "privacy_request", r.id().toString(), null,
        null, Map.of("bytes", archive.length)));
    log.info("PRIVACY_EXPORT_COMPLETED request={} bytes={}", r.id(), archive.length);
  }

  private static void add(ZipOutputStream zip, String name, byte[] content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content);
    zip.closeEntry();
  }

  // ----------------------------------------------------------------------------------------------- helpers

  private Request save(Request r) {
    store.update(r);
    return new Request(r.id(), r.accountId(), r.subjectRef(), r.kind(), r.status(), r.phase(), r.attempt(),
        r.services(), r.stepsJson(), r.phaseStartedAt(), r.nextCheckAt(), r.archiveKey(), r.archiveBytes(), r.error(),
        r.createdAt(), r.completedAt(), r.version() + 1);
  }

  private static Request with(Request r, String status, String phase, int attempt, String steps, Instant phaseStarted,
      @Nullable Instant nextCheck, @Nullable String archiveKey, @Nullable Long archiveBytes, @Nullable String error,
      @Nullable Instant completedAt) {
    return new Request(r.id(), r.accountId(), r.subjectRef(), r.kind(), status, phase, attempt, r.services(), steps,
        phaseStarted, nextCheck, archiveKey, archiveBytes, error, r.createdAt(),
        completedAt == null ? r.completedAt() : completedAt, r.version());
  }

  private ObjectNode steps(Request r) {
    JsonNode n = mapper.readTree(r.stepsJson());
    return n instanceof ObjectNode o ? o : mapper.createObjectNode();
  }

  private PrivacyView view(Request r) {
    String url = null;
    String expires = null;
    if (r.archiveKey() != null && r.completedAt() != null) {
      Instant until = r.completedAt().plus(DOWNLOAD_TTL);
      Duration left = Duration.between(clock.instant(), until);
      expires = until.toString();
      if (!left.isNegative() && !left.isZero()) {
        url = exports.presign(r.archiveKey(), left).toString();
      }
    }
    return new PrivacyView(r.id(), r.kind(), r.status(), r.phase(), r.attempt(), r.services(), steps(r),
        r.createdAt().toString(), r.completedAt() == null ? null : r.completedAt().toString(), r.error(), url, expires,
        r.archiveBytes());
  }

  private AdminPrivacyView adminView(Request r) {
    return new AdminPrivacyView(r.id(), r.accountId(), r.subjectRef(), r.kind(), r.status(), r.phase(), r.attempt(),
        r.services(), steps(r), r.phaseStartedAt().toString(),
        r.nextCheckAt() == null ? null : r.nextCheckAt().toString(), r.createdAt().toString(),
        r.completedAt() == null ? null : r.completedAt().toString(), r.error(), r.version());
  }

  private static String trim(String s) {
    return s.length() > 300 ? s.substring(0, 300) : s;
  }

  static DexterException notFound() {
    return new DexterException(AuditErrors.PRIVACY_REQUEST_NOT_FOUND, "No privacy request with that id");
  }
}
