package in.dexter.catalog.app;

import in.dexter.catalog.app.AdminCatalogStore.Card;
import in.dexter.catalog.app.AdminCatalogStore.Version;
import in.dexter.catalog.app.IngestionStore.CardRef;
import in.dexter.catalog.app.IngestionStore.ReviewItem;
import in.dexter.catalog.app.IngestionStore.Run;
import in.dexter.catalog.app.IngestionStore.Source;
import in.dexter.catalog.domain.CardDiff;
import in.dexter.catalog.domain.CardDocument;
import in.dexter.catalog.domain.CardExtractor;
import in.dexter.catalog.domain.Sitemaps;
import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The collector (MASTER_PLAN P10): runs crawl runs source by source with a checkpoint after each source, so a run
 * re-claimed after a crash continues where it stopped. Per page: polite wait for the host → SSRF-safe fetch →
 * SHA-256; the same hash as the latest snapshot of (source, url) ends there ("unchanged"); otherwise the content goes
 * to the object store (content-addressed key), and one transaction writes the snapshot, the extraction (HTML pages)
 * and, when the field-level diff against the card's published version is not empty, a review item (superseding the
 * card's pending one unless the diff is identical). Runs as actor role INGESTOR: it reads the published catalog and
 * never writes it — only the {@link Promoter} does, on approve.
 */
public final class IngestionRunner {
  private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);
  /** The worker's tenant: a system actor no token can carry (RLS: is_ingestor()). */
  public static final Tenant INGESTOR = new Tenant(new UUID(0, 0x1_6E57L), "INGESTOR", null);
  static final int MAX_ERRORS = 50;

  /** Collector settings: per-host politeness delay, stale claim age, attempts, sitemap fan-out. */
  public record Settings(Duration politeness, Duration staleAfter, int maxAttempts, int sitemapMaxUrls) {}

  /** Waits (politeness); tests may replace it. */
  public interface Sleeper {
    void sleep(Duration d) throws InterruptedException;
  }

  private final TenantTx tx;
  private final IngestionStore store;
  private final AdminCatalogStore catalog;
  private final Promoter promoter;
  private final PageFetcher fetcher;
  private final SnapshotStore snapshots;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final Settings settings;
  private final Sleeper sleeper;
  private final Map<String, Instant> lastFetch = new HashMap<>();

  public IngestionRunner(TenantTx tx, IngestionStore store, AdminCatalogStore catalog, Promoter promoter,
      PageFetcher fetcher, SnapshotStore snapshots, ObjectMapper mapper, Clock clock, Settings settings,
      Sleeper sleeper) {
    this.tx = tx;
    this.store = store;
    this.catalog = catalog;
    this.promoter = promoter;
    this.fetcher = fetcher;
    this.snapshots = snapshots;
    this.mapper = mapper;
    this.clock = clock;
    this.settings = settings;
    this.sleeper = sleeper == null ? d -> Thread.sleep(d.toMillis()) : sleeper;
  }

  static String initialCheckpoint() {
    return "{\"done\":0,\"stats\":{},\"errors\":[]}";
  }

  // ------------------------------------------------------------------ scheduler

  /** Queues one run for every due scheduled source (and moves their next run on); the run id or empty. */
  public Optional<UUID> scheduleDue() {
    return tx.write(INGESTOR, () -> {
      Instant now = now();
      List<Source> due = store.lockDueSources(now, IngestionAdminService.MAX_RUN_SOURCES);
      if (due.isEmpty()) {
        return Optional.<UUID>empty();
      }
      List<UUID> ids = new ArrayList<>();
      for (Source s : due) {
        if (s.enabled()) {
          ids.add(s.id());
        }
        store.markScheduled(s.id(), Schedules.next(s.scheduleCron(), now), now);
      }
      if (ids.isEmpty()) {
        return Optional.<UUID>empty();
      }
      UUID id = Ids.newId();
      store.insertRun(new Run(id, "schedule", null, ids, "queued", initialCheckpoint(), 0, null, now, null, null, 0));
      return Optional.of(id);
    });
  }

  // ------------------------------------------------------------------ runs

  /** Claims one run and executes its remaining sources; the run id, or empty when nothing is waiting. */
  public Optional<UUID> runOnce() {
    Optional<Run> claimed = tx.write(INGESTOR, () -> {
      Instant now = now();
      return store.claimRun(now, now.minus(settings.staleAfter()), settings.maxAttempts());
    });
    if (claimed.isEmpty()) {
      return Optional.empty();
    }
    UUID id = claimed.get().id();
    try {
      while (step(id)) {
        // next source
      }
    } catch (RuntimeException e) {
      // the run stays "running": after staleAfter another claim continues it from the checkpoint
      log.warn("INGESTION_RUN_ERROR run={} error={}", id, e.toString());
    }
    return Optional.of(id);
  }

  /**
   * Processes the run's next source and saves the checkpoint; completes the run after the last one. Returns true
   * while sources remain.
   */
  public boolean step(UUID runId) {
    Run run = tx.read(INGESTOR, () -> store.run(runId)).orElseThrow();
    if (!"running".equals(run.status())) {
      return false;
    }
    Map<String, Object> cp = checkpoint(run.checkpointJson());
    int done = ((Number) cp.getOrDefault("done", 0)).intValue();
    if (done >= run.sourceIds().size()) {
      complete(run, cp);
      return false;
    }
    UUID sourceId = run.sourceIds().get(done);
    Optional<Source> source = tx.read(INGESTOR, () -> store.source(sourceId));
    if (source.isEmpty() || !source.get().enabled()) {
      count(cp, "skipped");
    } else {
      process(run, source.get(), cp);
    }
    cp.put("done", done + 1);
    boolean more = done + 1 < run.sourceIds().size();
    String json = mapper.writeValueAsString(cp);
    tx.writeVoid(INGESTOR, () -> {
      Instant now = now();
      source.ifPresent(s -> store.markSourceRun(s.id(), now));
      if (more) {
        store.saveCheckpoint(runId, json, now);
      } else {
        store.finishRun(runId, "completed", null, json, now);
      }
    });
    return more;
  }

  private void complete(Run run, Map<String, Object> cp) {
    String json = mapper.writeValueAsString(cp);
    tx.writeVoid(INGESTOR, () -> store.finishRun(run.id(), "completed", null, json, now()));
  }

  // ------------------------------------------------------------------ one source

  private void process(Run run, Source s, Map<String, Object> cp) {
    List<String> urls;
    if ("sitemap".equals(s.kind())) {
      PageFetcher.Fetched map = fetch(s.url(), PageFetcher.Expect.XML, cp, s);
      if (map == null) {
        return;
      }
      urls = Sitemaps.pageUrls(new String(map.body(), StandardCharsets.UTF_8), URI.create(s.url()),
          settings.sitemapMaxUrls());
      count(cp, "sitemapPages", urls.size());
    } else {
      urls = List.of(s.url());
    }
    for (String url : urls) {
      PageFetcher.Fetched page = fetch(url, expect(s.kind()), cp, s);
      if (page != null) {
        store(run, s, url, page, cp);
      }
    }
  }

  private PageFetcher.Fetched fetch(String url, PageFetcher.Expect expect, Map<String, Object> cp, Source s) {
    try {
      polite(URI.create(url));
      PageFetcher.Fetched f = fetcher.fetch(URI.create(url), expect);
      count(cp, "fetched");
      return f;
    } catch (PageFetcher.FetchFailure e) {
      error(cp, s.id(), url, e.code());
      return null;
    } catch (IllegalArgumentException e) {
      error(cp, s.id(), url, "BAD_URL");
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  private void store(Run run, Source s, String url, PageFetcher.Fetched page, Map<String, Object> cp) {
    byte[] hash = sha256(page.body());
    Optional<byte[]> latest = tx.read(INGESTOR, () -> store.latestHash(s.id(), url));
    if (latest.isPresent() && Arrays.equals(latest.get(), hash)) {
      count(cp, "unchanged");
      return;
    }
    String key = "snapshots/" + s.id() + "/" + HexFormat.of().formatHex(hash);
    snapshots.put(key, page.body(), page.contentType());
    boolean html = "html".equals(s.kind()) || "sitemap".equals(s.kind());
    CardExtractor.Extraction x = html ? CardExtractor.extract(new String(page.body(), StandardCharsets.UTF_8)) : null;
    tx.writeVoid(INGESTOR, () -> {
      Optional<byte[]> again = store.latestHash(s.id(), url);
      if (again.isPresent() && Arrays.equals(again.get(), hash)) {
        count(cp, "unchanged");
        return;
      }
      Instant now = now();
      UUID snapshotId = Ids.newId();
      store.insertSnapshot(new IngestionStore.Snapshot(snapshotId, s.id(), run.id(), url, now, hash,
          page.contentType(), page.body().length, key));
      count(cp, "snapshots");
      if (x == null) {
        count(cp, "storedOnly"); // pdf / image: kept for review by eye; extractor v1 reads HTML only
        return;
      }
      Card card = match(s, url, x).orElse(null);
      UUID extractionId = Ids.newId();
      store.insertExtraction(new IngestionStore.Extraction(extractionId, snapshotId, card == null ? null : card.id(),
          mapper.writeValueAsString(extracted(x)), CardExtractor.VERSION, x.confidence()));
      count(cp, "extractions");
      if (card == null) {
        count(cp, "unmatched");
        return;
      }
      if (!"published".equals(card.status()) || card.currentVersion() == null || x.isEmpty()) {
        count(cp, "notReviewable");
        return;
      }
      Version v = catalog.currentVersion(card.id()).orElseThrow();
      CardDocument base = promoter.document(v);
      List<CardDiff.Change> changes = CardDiff.compute(base, x);
      if (changes.isEmpty()) {
        count(cp, "noChange");
        return;
      }
      Map<String, Object> diff = new LinkedHashMap<>();
      diff.put("baseVersion", v.version());
      diff.put("changes", changes);
      String diffJson = mapper.writeValueAsString(diff);
      Optional<ReviewItem> pending = store.lockPendingFor(card.id());
      if (pending.isPresent()) {
        JsonNode a = mapper.readTree(pending.get().diffJson());
        if (a.equals(mapper.readTree(diffJson))) {
          count(cp, "duplicateDiff");
          return;
        }
        store.decide(pending.get().id(), "superseded", null, null, now);
        count(cp, "superseded");
      }
      double confidence = changes.stream().mapToDouble(CardDiff.Change::confidence).min().orElse(0);
      store.insertReview(Ids.newId(), extractionId, card.id(), v.version(), diffJson, confidence, now);
      count(cp, "reviewItems");
    });
  }

  /**
   * The card an extraction is about: the source's card; else (sitemap pages) the issuer's one card whose slug is
   * the URL's last path segment or whose normalised name equals the page title's.
   */
  private Optional<Card> match(Source s, String url, CardExtractor.Extraction x) {
    if (s.cardProductId() != null) {
      return catalog.find(s.cardProductId());
    }
    List<CardRef> cards = store.issuerCards(s.issuerId());
    String path = URI.create(url).getPath();
    String last = path == null ? "" : path.replaceAll("/+$", "");
    last = last.substring(last.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    String title = norm(x.name());
    List<CardRef> hits = new ArrayList<>();
    for (CardRef c : cards) {
      if (c.slug().equals(last) || !title.isEmpty() && norm(c.name()).equals(title)) {
        hits.add(c);
      }
    }
    return hits.size() == 1 ? catalog.find(hits.getFirst().id()) : Optional.empty();
  }

  static String norm(String name) {
    if (name == null) {
      return "";
    }
    String n = name.toLowerCase(Locale.ROOT).replaceAll("^mock_", "").replaceAll("\\|.*$", "")
        .replaceAll("\\bcredit\\s+card\\b|\\bcard\\b", " ").replaceAll("[^a-z0-9]+", "");
    return n;
  }

  private static Map<String, Object> extracted(CardExtractor.Extraction x) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", x.name());
    Map<String, Object> fields = new LinkedHashMap<>();
    x.fields().forEach((k, f) -> fields.put(k, Map.of("value", f.value(), "confidence", f.confidence())));
    m.put("fields", fields);
    List<Map<String, Object>> rules = new ArrayList<>();
    for (CardExtractor.RuleHit r : x.rules()) {
      Map<String, Object> rm = new LinkedHashMap<>();
      rm.put("categoryPath", r.categoryPath());
      rm.put("rateBps", r.rateBps());
      rm.put("confidence", r.confidence());
      rm.put("phrase", r.phrase());
      rules.add(rm);
    }
    m.put("rules", rules);
    m.put("unmapped", x.unmapped());
    return m;
  }

  // ------------------------------------------------------------------ helpers

  private void polite(URI url) throws InterruptedException {
    String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
    Instant last;
    synchronized (lastFetch) {
      last = lastFetch.get(host);
    }
    if (last != null && !settings.politeness().isZero()) {
      Duration wait = Duration.between(clock.instant(), last.plus(settings.politeness()));
      if (!wait.isNegative() && !wait.isZero()) {
        sleeper.sleep(wait);
      }
    }
    synchronized (lastFetch) {
      lastFetch.put(host, clock.instant());
    }
  }

  private static PageFetcher.Expect expect(String kind) {
    return switch (kind) {
      case "pdf" -> PageFetcher.Expect.PDF;
      case "image" -> PageFetcher.Expect.IMAGE;
      default -> PageFetcher.Expect.HTML;
    };
  }

  private Map<String, Object> checkpoint(String json) {
    Map<String, Object> m = mapper.readValue(json == null || json.isBlank() ? initialCheckpoint() : json,
        new TypeReference<LinkedHashMap<String, Object>>() {});
    m.putIfAbsent("done", 0);
    m.putIfAbsent("stats", new LinkedHashMap<String, Object>());
    m.putIfAbsent("errors", new ArrayList<Object>());
    return m;
  }

  @SuppressWarnings("unchecked")
  private static void count(Map<String, Object> cp, String name, int by) {
    Map<String, Object> stats = (Map<String, Object>) cp.get("stats");
    Object v = stats.get(name);
    stats.put(name, (v == null ? 0 : ((Number) v).intValue()) + by);
  }

  private static void count(Map<String, Object> cp, String name) {
    count(cp, name, 1);
  }

  @SuppressWarnings("unchecked")
  private static void error(Map<String, Object> cp, UUID sourceId, String url, String code) {
    count(cp, "errors");
    List<Object> errors = (List<Object>) cp.get("errors");
    if (errors.size() < MAX_ERRORS) {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("sourceId", sourceId.toString());
      e.put("url", url);
      e.put("code", Objects.requireNonNullElse(code, "FETCH_FAILED"));
      errors.add(e);
    }
  }

  static byte[] sha256(byte[] b) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(b);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
