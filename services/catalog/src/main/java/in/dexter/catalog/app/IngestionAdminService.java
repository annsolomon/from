package in.dexter.catalog.app;

import in.dexter.catalog.app.AdminCatalogStore.Card;
import in.dexter.catalog.app.AdminCatalogStore.Issuer;
import in.dexter.catalog.app.IngestionStore.Run;
import in.dexter.catalog.app.IngestionStore.Source;
import in.dexter.catalog.domain.CatalogErrors;
import in.dexter.catalog.domain.FetchPolicy;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.NotFoundException;
import in.dexter.core.error.ValidationException;
import in.dexter.core.error.VersionConflictException;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import in.dexter.db.tenant.TenantTx;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ingestion administration (MASTER_PLAN P10, editors): sources (CRUD, If-Match on PATCH, soft delete) and crawl runs
 * (queued here, executed by {@link IngestionRunner}). A source URL must pass {@link FetchPolicy} when it is saved, so
 * an editor learns at once that a host is not on the allowlist; the collector checks it again on every fetch.
 */
public final class IngestionAdminService {
  static final Set<String> KINDS = Set.of("html", "pdf", "sitemap", "image");
  static final int MAX_RUN_SOURCES = 500;

  public record NewSource(String issuerCode, UUID cardProductId, String url, String kind, String scheduleCron,
      Boolean enabled) {}

  public record SourceView(UUID id, String issuerCode, UUID cardProductId, String url, String host, String kind,
      String scheduleCron, boolean enabled, String nextRunAt, String lastRunAt, String createdAt, String updatedAt,
      int version) {}

  public record RunView(UUID id, String trigger, String status, List<UUID> sourceIds, int attempts, String errorCode,
      JsonNode checkpoint, String createdAt, String startedAt, String finishedAt) {}

  private final TenantTx tx;
  private final IngestionStore store;
  private final AdminCatalogStore catalog;
  private final FetchPolicy policy;
  private final ObjectMapper mapper;
  private final Clock clock;

  public IngestionAdminService(TenantTx tx, IngestionStore store, AdminCatalogStore catalog, FetchPolicy policy,
      ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.store = store;
    this.catalog = catalog;
    this.policy = policy;
    this.mapper = mapper;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ sources

  public SourceView create(Tenant admin, NewSource cmd) {
    if (cmd == null) {
      throw new ValidationException(List.of(new FieldError("body", "REQUIRED", "A source")));
    }
    List<FieldError> e = new ArrayList<>();
    String url = checkUrl(e, cmd.url());
    if (!KINDS.contains(cmd.kind())) {
      e.add(new FieldError("kind", "INVALID", "html, pdf, sitemap or image"));
    }
    if ("sitemap".equals(cmd.kind()) && cmd.cardProductId() != null) {
      e.add(new FieldError("cardProductId", "NOT_ALLOWED", "A sitemap's pages are matched to cards by name"));
    }
    checkCron(e, cmd.scheduleCron());
    if (cmd.issuerCode() == null || cmd.issuerCode().isBlank()) {
      e.add(new FieldError("issuerCode", "REQUIRED", "The issuer whose pages these are"));
    }
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    return tx.write(admin, () -> {
      Issuer issuer = catalog.issuerByCode(cmd.issuerCode()).orElseThrow(() -> new ValidationException(List.of(
          new FieldError("issuerCode", "UNKNOWN_ISSUER", "No issuer " + cmd.issuerCode()))));
      checkCard(issuer, cmd.cardProductId());
      if (store.urlTaken(url, null)) {
        throw new DexterException(CatalogErrors.SOURCE_EXISTS, "A source already fetches " + url);
      }
      Instant now = now();
      String cron = blankToNull(cmd.scheduleCron());
      boolean enabled = !Boolean.FALSE.equals(cmd.enabled());
      Source s = new Source(Ids.newId(), issuer.id(), issuer.code(), cmd.cardProductId(), url,
          FetchPolicy.hostKey(URI.create(url)), cmd.kind(), cron, enabled, cron == null ? null : Schedules.next(cron, now),
          null, now, now, 0);
      store.insertSource(s);
      return view(s);
    });
  }

  public Page<SourceView> sources(Tenant admin, PageRequest page) {
    return tx.read(admin, () -> store.sources(page)).map(this::view);
  }

  public SourceView source(Tenant admin, UUID id) {
    return view(tx.read(admin, () -> store.source(id)).orElseThrow(() -> new NotFoundException("source")));
  }

  /**
   * JSON merge patch (If-Match: the source version) of {@code url}, {@code kind}, {@code cardProductId},
   * {@code scheduleCron} and {@code enabled}; null clears the card or the schedule.
   */
  public SourceView patch(Tenant admin, UUID id, int expectedVersion, JsonNode patch) {
    if (patch == null || !patch.isObject()) {
      throw new ValidationException(List.of(new FieldError("body", "INVALID", "A JSON object")));
    }
    List<FieldError> e = new ArrayList<>();
    for (String name : patch.propertyNames()) {
      if (!Set.of("url", "kind", "cardProductId", "scheduleCron", "enabled").contains(name)) {
        e.add(new FieldError(name, "UNKNOWN_FIELD", "Not patchable"));
      }
    }
    if (!e.isEmpty()) {
      throw new ValidationException(e);
    }
    return tx.write(admin, () -> {
      Source s = store.lockSource(id).orElseThrow(() -> new NotFoundException("source"));
      if (s.version() != expectedVersion) {
        throw new VersionConflictException("source", expectedVersion);
      }
      String url = patch.has("url") ? checkUrl(e, text(patch.get("url"))) : s.url();
      String kind = patch.has("kind") ? text(patch.get("kind")) : s.kind();
      if (!KINDS.contains(kind)) {
        e.add(new FieldError("kind", "INVALID", "html, pdf, sitemap or image"));
      }
      UUID card = s.cardProductId();
      if (patch.has("cardProductId")) {
        String c = text(patch.get("cardProductId"));
        try {
          card = c == null ? null : UUID.fromString(c);
        } catch (IllegalArgumentException ex) {
          e.add(new FieldError("cardProductId", "INVALID", "A card product id"));
        }
      }
      if ("sitemap".equals(kind) && card != null) {
        e.add(new FieldError("cardProductId", "NOT_ALLOWED", "A sitemap's pages are matched to cards by name"));
      }
      String cron = patch.has("scheduleCron") ? blankToNull(text(patch.get("scheduleCron"))) : s.scheduleCron();
      checkCron(e, cron);
      boolean enabled = s.enabled();
      if (patch.has("enabled")) {
        if (!patch.get("enabled").isBoolean()) {
          e.add(new FieldError("enabled", "INVALID", "true or false"));
        } else {
          enabled = patch.get("enabled").asBoolean();
        }
      }
      if (!e.isEmpty()) {
        throw new ValidationException(e);
      }
      if (card != null && !card.equals(s.cardProductId())) {
        Issuer issuer = catalog.issuer(s.issuerId()).orElseThrow();
        checkCard(issuer, card);
      }
      if (!url.equals(s.url()) && store.urlTaken(url, id)) {
        throw new DexterException(CatalogErrors.SOURCE_EXISTS, "A source already fetches " + url);
      }
      Instant now = now();
      Instant next = cron == null ? null : cron.equals(s.scheduleCron()) && s.nextRunAt() != null ? s.nextRunAt()
          : Schedules.next(cron, now);
      Source u = new Source(s.id(), s.issuerId(), s.issuerCode(), card, url, FetchPolicy.hostKey(URI.create(url)), kind,
          cron, enabled, next, s.lastRunAt(), s.createdAt(), now, s.version() + 1);
      if (!store.updateSource(u, expectedVersion, now)) {
        throw new VersionConflictException("source", expectedVersion);
      }
      return view(u);
    });
  }

  /** Soft delete: the source stops running; its snapshots and review items stay. */
  public void delete(Tenant admin, UUID id) {
    tx.writeVoid(admin, () -> {
      store.lockSource(id).orElseThrow(() -> new NotFoundException("source"));
      store.deleteSource(id, now());
    });
  }

  // ------------------------------------------------------------------ runs

  /** Queues a crawl run over the given sources (null = every live, enabled source). */
  public RunView startRun(Tenant admin, List<UUID> sourceIds) {
    if (sourceIds != null && (sourceIds.isEmpty() || sourceIds.size() > MAX_RUN_SOURCES)) {
      throw new ValidationException(List.of(new FieldError("sourceIds", "INVALID", "1-500 source ids, or omit")));
    }
    return tx.write(admin, () -> {
      List<UUID> ids = store.runnableSources(sourceIds == null ? null : List.copyOf(sourceIds));
      if (ids.isEmpty()) {
        throw new ValidationException(List.of(new FieldError("sourceIds", "NONE_RUNNABLE",
            "No live, enabled source to run")));
      }
      if (sourceIds != null && ids.size() != Set.copyOf(sourceIds).size()) {
        throw new ValidationException(List.of(new FieldError("sourceIds", "NOT_RUNNABLE",
            "Unknown, deleted or disabled sources in the list")));
      }
      Run r = new Run(Ids.newId(), "manual", admin.accountId(), ids, "queued", IngestionRunner.initialCheckpoint(), 0,
          null, now(), null, null, 0);
      store.insertRun(r);
      return runView(r);
    });
  }

  public RunView run(Tenant admin, UUID id) {
    return runView(tx.read(admin, () -> store.run(id)).orElseThrow(() -> new NotFoundException("run")));
  }

  public Page<RunView> runs(Tenant admin, PageRequest page) {
    return tx.read(admin, () -> store.runs(page)).map(this::runView);
  }

  // ------------------------------------------------------------------ helpers

  private String checkUrl(List<FieldError> e, String raw) {
    if (raw == null || raw.isBlank() || raw.length() > 2048) {
      e.add(new FieldError("url", "INVALID", "An https URL on an allowed issuer host"));
      return raw;
    }
    String url = raw.strip();
    Optional<FetchPolicy.Refusal> r = policy.checkUrl(url);
    if (r.isPresent()) {
      e.add(new FieldError("url", r.get().name(), switch (r.get()) {
        case HOST_NOT_ALLOWED -> "The host is not on the ingestion allowlist";
        case SCHEME_NOT_ALLOWED -> "Only https";
        case PORT_NOT_ALLOWED -> "Only the default port";
        case CREDENTIALS_IN_URL -> "No credentials in the URL";
        default -> "Not a fetchable URL";
      }));
      return url;
    }
    return URI.create(url).normalize().toString();
  }

  private static void checkCron(List<FieldError> e, String cron) {
    if (cron != null && !cron.isBlank() && Schedules.parse(cron).isEmpty()) {
      e.add(new FieldError("scheduleCron", "INVALID",
          "Five-field cron (minute hour day month weekday, IST), at most hourly"));
    }
  }

  private void checkCard(Issuer issuer, UUID cardId) {
    if (cardId == null) {
      return;
    }
    Card c = catalog.find(cardId).orElseThrow(() -> new ValidationException(List.of(
        new FieldError("cardProductId", "UNKNOWN_CARD", "No such card product"))));
    if (!c.issuerId().equals(issuer.id())) {
      throw new ValidationException(List.of(new FieldError("cardProductId", "OTHER_ISSUER",
          "The card belongs to another issuer")));
    }
  }

  private static String text(JsonNode n) {
    return n == null || n.isNull() ? null : n.isString() ? n.asString() : n.toString();
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  SourceView view(Source s) {
    return new SourceView(s.id(), s.issuerCode(), s.cardProductId(), s.url(), s.host(), s.kind(), s.scheduleCron(),
        s.enabled(), str(s.nextRunAt()), str(s.lastRunAt()), str(s.createdAt()), str(s.updatedAt()), s.version());
  }

  RunView runView(Run r) {
    return new RunView(r.id(), r.trigger(), r.status(), r.sourceIds(), r.attempts(), r.errorCode(),
        mapper.readTree(r.checkpointJson() == null ? "{}" : r.checkpointJson()), str(r.createdAt()),
        str(r.startedAt()), str(r.finishedAt()));
  }

  private static String str(Instant i) {
    return i == null ? null : i.toString();
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
