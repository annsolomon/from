package in.dexter.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.catalog.app.IngestionRunner;
import in.dexter.catalog.app.PageFetcher;
import in.dexter.catalog.support.Cards;
import in.dexter.catalog.support.IngestionIT;
import in.dexter.catalog.support.MockIssuerSite;
import in.dexter.catalog.support.MockIssuerSite.Page;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The collector (MASTER_PLAN P10): the SSRF-safe fetcher (localhost, 169.254.x, private ranges behind allowlisted
 * names, redirects to private addresses, size/type/redirect caps), per-host politeness on a sitemap, card matching by
 * URL slug, resumable checkpoints after a crash, abandoned runs, the scheduler.
 */
class IngestionCollectorIT extends IngestionIT {
  @Autowired
  PageFetcher fetcher;
  @Autowired
  IngestionRunner runner;

  static String code(Runnable r) {
    try {
      r.run();
      return "FETCHED";
    } catch (PageFetcher.FetchFailure e) {
      return e.code();
    }
  }

  String fetch(String url) {
    return code(() -> fetcher.fetch(URI.create(url), PageFetcher.Expect.HTML));
  }

  @Test
  void theFetcherRefusesPrivateTargetsAndCapsEverything() {
    SITE.put("/ok", Page.html("<h1>ok</h1>"));
    assertThat(fetch(SITE.url("/ok"))).isEqualTo("FETCHED");
    // never on the allowlist
    assertThat(fetch("https://localhost/")).isEqualTo("HOST_NOT_ALLOWED");
    assertThat(fetch("https://127.0.0.1/")).isEqualTo("HOST_NOT_ALLOWED");
    assertThat(fetch("https://169.254.169.254/latest/meta-data/")).isEqualTo("HOST_NOT_ALLOWED");
    assertThat(fetch("http://localhost:" + SITE.port() + "/ok")).as("same server, not the dev host")
        .isEqualTo("SCHEME_NOT_ALLOWED");
    // allowlisted names that resolve to private addresses (DNS rebinding / internal names)
    assertThat(fetch("https://meta.issuer.test/latest/meta-data/")).isEqualTo("PRIVATE_ADDRESS");
    assertThat(fetch("https://loop.issuer.test/")).isEqualTo("PRIVATE_ADDRESS");
    assertThat(fetch("https://internal.issuer.test/")).isEqualTo("PRIVATE_ADDRESS");
    assertThat(fetch("https://ula.issuer.test/")).isEqualTo("PRIVATE_ADDRESS");
    assertThat(fetch("https://mapped.issuer.test/")).isEqualTo("PRIVATE_ADDRESS");
    assertThat(fetch("https://nowhere.issuer.test/")).isEqualTo("UNKNOWN_HOST");
    // redirects are checked hop by hop
    SITE.put("/to-meta", Page.redirect("https://meta.issuer.test/latest/meta-data/iam"));
    SITE.put("/to-ip", Page.redirect("http://169.254.169.254/latest/meta-data/"));
    SITE.put("/to-local", Page.redirect("http://localhost:" + SITE.port() + "/ok"));
    SITE.put("/to-ok", Page.redirect("/ok"));
    SITE.put("/loop", Page.redirect("/loop"));
    assertThat(fetch(SITE.url("/to-meta"))).isEqualTo("PRIVATE_ADDRESS");
    assertThat(fetch(SITE.url("/to-ip"))).isEqualTo("SCHEME_NOT_ALLOWED");
    assertThat(fetch(SITE.url("/to-local"))).isEqualTo("SCHEME_NOT_ALLOWED");
    assertThat(fetch(SITE.url("/to-ok"))).as("a relative redirect on the same host").isEqualTo("FETCHED");
    assertThat(fetch(SITE.url("/loop"))).isEqualTo("TOO_MANY_REDIRECTS");
    assertThat(SITE.hits("/loop")).hasSize(4);
    // size, type, status
    SITE.put("/big", Page.html("x".repeat(300_000)));
    SITE.put("/json", Page.of("application/json", "{}"));
    assertThat(fetch(SITE.url("/big"))).isEqualTo("TOO_LARGE");
    assertThat(fetch(SITE.url("/json"))).isEqualTo("WRONG_CONTENT_TYPE");
    assertThat(fetch(SITE.url("/missing"))).isEqualTo("HTTP_404");
    assertThatThrownBy(() -> fetcher.fetch(URI.create(SITE.url("/ok")), PageFetcher.Expect.PDF))
        .isInstanceOf(PageFetcher.FetchFailure.class).hasMessageContaining("WRONG_CONTENT_TYPE");
  }

  @Test
  void blockedSourcesAreRefusedAtSaveOrRecordedPerFetch() {
    String issuer = newIssuer();
    for (String[] c : List.of(new String[] {"https://localhost/cards", "HOST_NOT_ALLOWED"},
        new String[] {"https://169.254.169.254/latest/meta-data/", "HOST_NOT_ALLOWED"},
        new String[] {"https://10.0.0.7/cards", "HOST_NOT_ALLOWED"},
        new String[] {"http://www.issuer.test/cards", "SCHEME_NOT_ALLOWED"},
        new String[] {"https://u:p@www.issuer.test/cards", "CREDENTIALS_IN_URL"},
        new String[] {"https://www.issuer.test:8443/cards", "PORT_NOT_ALLOWED"})) {
      Res r = createSource(issuer, null, c[0], "html");
      assertThat(r.code()).as(c[0]).isEqualTo("VALIDATION_FAILED");
      assertThat(r.json().get("errors").get(0).get("code").asString()).as(c[0]).isEqualTo(c[1]);
    }
    String tag = Cards.suffix();
    SITE.put("/hop/" + tag, Page.redirect("https://internal.issuer.test/admin"));
    UUID meta = source(issuer, null, "https://meta.issuer.test/" + tag, "html");
    UUID hop = source(issuer, null, SITE.url("/hop/" + tag), "html");
    JsonNode run = crawl(meta, hop);
    assertThat(run.get("status").asString()).isEqualTo("completed");
    assertThat(stat(run, "errors")).isEqualTo(2);
    assertThat(stat(run, "snapshots")).isZero();
    List<String> codes = new ArrayList<>();
    run.get("checkpoint").get("errors").forEach(e -> codes.add(e.get("code").asString()));
    assertThat(codes).containsExactly("PRIVATE_ADDRESS", "PRIVATE_ADDRESS");
  }

  @Test
  void aSitemapIsCrawledPolitelyAndPagesMatchCardsBySlug() {
    String issuer = newIssuer();
    String tag = Cards.suffix();
    UUID one = card(issuer, "ing-map-one-" + tag, "Map One " + tag);
    UUID two = card(issuer, "ing-map-two-" + tag, "Map Two " + tag);
    String base = "/map-" + tag;
    SITE.put(base + "/sitemap.xml", Page.of("application/xml", "<?xml version=\"1.0\"?><urlset>"
        + "<url><loc>" + SITE.url(base + "/cards/ing-map-one-" + tag) + "</loc></url>"
        + "<url><loc>" + SITE.url(base + "/cards/ing-map-two-" + tag) + "</loc></url>"
        + "<url><loc>" + SITE.url(base + "/cards/unknown-" + tag) + "</loc></url>"
        + "<url><loc>https://elsewhere.issuer.test/cards/x</loc></url></urlset>"));
    SITE.put(base + "/cards/ing-map-one-" + tag, Page.html(page("Map One " + tag, 500, 500, 5, null)));
    SITE.put(base + "/cards/ing-map-two-" + tag, Page.html(page("Map Two " + tag, 500, 1250, 5, null)));
    SITE.put(base + "/cards/unknown-" + tag, Page.html(page("Something Else " + tag, 500, 500, 5, null)));
    UUID map = source(issuer, null, SITE.url(base + "/sitemap.xml"), "sitemap");

    JsonNode run = crawl(map);
    assertThat(stat(run, "sitemapPages")).as(run.toString()).isEqualTo(3);
    assertThat(stat(run, "snapshots")).isEqualTo(3);
    assertThat(stat(run, "unmatched")).isEqualTo(1);
    assertThat(stat(run, "noChange")).isEqualTo(1);
    assertThat(stat(run, "reviewItems")).isEqualTo(1);
    assertThat(reviews(one, "pending")).isEmpty();
    assertThat(reviews(two, "pending")).singleElement()
        .satisfies(i -> assertThat(i.get("changes").get(0).get("to").asLong()).isEqualTo(125_000));

    List<MockIssuerSite.Hit> hits = SITE.allHits().stream().filter(h -> h.path().startsWith(base)).toList();
    assertThat(hits).hasSize(4);
    for (int i = 1; i < hits.size(); i++) {
      assertThat(Duration.between(hits.get(i - 1).at(), hits.get(i).at()))
          .as("politeness between fetches of one host").isGreaterThanOrEqualTo(Duration.ofMillis(140));
    }
  }

  @Test
  void aCrashedRunResumesFromItsCheckpointAndDyingRunsAreAbandoned() {
    String issuer = newIssuer();
    String tag = Cards.suffix();
    SITE.put("/res-a/" + tag, Page.html(page("Res A " + tag, 500, 500, 5, null)));
    SITE.put("/res-b/" + tag, Page.html(page("Res B " + tag, 500, 500, 5, null)));
    UUID a = source(issuer, null, SITE.url("/res-a/" + tag), "html");
    UUID b = source(issuer, null, SITE.url("/res-b/" + tag), "html");
    Res queued = as(editor(), "POST", "/v1/admin/ingestion/runs", Map.of("sourceIds", List.of(a, b)));
    UUID run = UUID.fromString(queued.json().get("id").asString());
    // a worker claimed it, finished source a, saved the checkpoint and died 11 minutes ago
    ADMIN.sql("UPDATE catalog.crawl_runs SET status = 'running', attempts = 1, started_at = now() - interval '12 min',"
            + " claimed_at = now() - interval '11 min', checkpoint = '{\"done\":1,\"stats\":{\"fetched\":1},"
            + "\"errors\":[]}' WHERE id = :id").param("id", run).update();
    worker.drain();
    JsonNode done = as(editor(), "GET", "/v1/admin/ingestion/runs/" + run, null).json();
    assertThat(done.get("status").asString()).as(done.toString()).isEqualTo("completed");
    assertThat(done.get("attempts").asInt()).isEqualTo(2);
    assertThat(stat(done, "fetched")).as("the saved stats continue").isEqualTo(2);
    assertThat(SITE.hits("/res-a/" + tag)).as("source a is not fetched again").isEmpty();
    assertThat(SITE.hits("/res-b/" + tag)).hasSize(1);

    Res again = as(editor(), "POST", "/v1/admin/ingestion/runs", Map.of("sourceIds", List.of(a)));
    UUID dying = UUID.fromString(again.json().get("id").asString());
    ADMIN.sql("UPDATE catalog.crawl_runs SET status = 'running', attempts = 3, started_at = now() - interval '1 hour',"
        + " claimed_at = now() - interval '40 min' WHERE id = :id").param("id", dying).update();
    worker.drain();
    JsonNode abandoned = as(editor(), "GET", "/v1/admin/ingestion/runs/" + dying, null).json();
    assertThat(abandoned.get("status").asString()).isEqualTo("failed");
    assertThat(abandoned.get("errorCode").asString()).isEqualTo("RUN_ABANDONED");
  }

  @Test
  void dueScheduledSourcesAreQueued() {
    String issuer = newIssuer();
    String tag = Cards.suffix();
    SITE.put("/sched/" + tag, Page.html(page("Sched " + tag, 500, 500, 5, null)));
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("issuerCode", issuer);
    body.put("url", SITE.url("/sched/" + tag));
    body.put("kind", "html");
    body.put("scheduleCron", "*/5 * * * *");
    assertThat(as(editor(), "POST", "/v1/admin/ingestion/sources", body).code()).as("more often than hourly")
        .isEqualTo("VALIDATION_FAILED");
    body.put("scheduleCron", "30 3 * * 1");
    Res created = as(editor(), "POST", "/v1/admin/ingestion/sources", body);
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    UUID id = UUID.fromString(created.json().get("id").asString());
    assertThat(created.json().get("nextRunAt").isNull()).isFalse();
    // drain earlier scheduled work, make this source due, schedule
    while (runner.scheduleDue().isPresent()) {
      worker.drain();
    }
    ADMIN.sql("UPDATE catalog.sources SET next_run_at = now() - interval '1 minute' WHERE id = :id").param("id", id)
        .update();
    UUID run = runner.scheduleDue().orElseThrow();
    JsonNode r = as(editor(), "GET", "/v1/admin/ingestion/runs/" + run, null).json();
    assertThat(r.get("trigger").asString()).isEqualTo("schedule");
    assertThat(r.get("sourceIds")).extracting(JsonNode::asString).contains(id.toString());
    JsonNode s = as(editor(), "GET", "/v1/admin/ingestion/sources/" + id, null).json();
    assertThat(java.time.Instant.parse(s.get("nextRunAt").asString())).isAfter(java.time.Instant.now());
    worker.drain();
    assertThat(SITE.hits("/sched/" + tag)).hasSize(1);
  }
}
