package in.dexter.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.catalog.support.Cards;
import in.dexter.catalog.support.IngestionIT;
import in.dexter.catalog.support.MockIssuerSite.Page;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The pipeline collector → review → promoter (MASTER_PLAN P10 tests): unchanged content makes no review item; a changed
 * fee makes one item with the right diff; approve publishes through the promoter and a second approve (sequential or
 * concurrent) creates no second version; newer items supersede, stale items and pending drafts are refused.
 */
class IngestionPipelineIT extends IngestionIT {

  @Test
  void unchangedContentMakesNoReviewItem() {
    String issuer = newIssuer();
    String tag = Cards.suffix();
    UUID card = card(issuer, "ing-same-" + tag, "Same Card " + tag);
    SITE.put("/same/" + tag, Page.html(page("Same Card " + tag, 500, 500, 5, null)));
    UUID src = source(issuer, card, SITE.url("/same/" + tag), "html");

    JsonNode first = crawl(src);
    assertThat(first.get("status").asString()).as(first.toString()).isEqualTo("completed");
    assertThat(stat(first, "snapshots")).isEqualTo(1);
    assertThat(stat(first, "extractions")).isEqualTo(1);
    assertThat(stat(first, "noChange")).as("the page states what is published").isEqualTo(1);
    assertThat(stat(first, "reviewItems")).isZero();

    JsonNode second = crawl(src);
    assertThat(stat(second, "unchanged")).as("same content hash").isEqualTo(1);
    assertThat(stat(second, "snapshots")).isZero();
    assertThat(ADMIN.sql("SELECT count(1) FROM catalog.snapshots WHERE source_id = :s").param("s", src)
        .query(Long.class).single()).isEqualTo(1);
    assertThat(reviews(card, "pending")).isEmpty();
    assertThat(SNAPSHOTS.objects.keySet()).anyMatch(k -> k.startsWith("snapshots/" + src + "/"));
    assertThat(versions(card)).isEqualTo(1);
  }

  @Test
  void aChangedFeeIsOneReviewItemAndApproveIsIdempotent() throws Exception {
    String issuer = newIssuer();
    String tag = Cards.suffix();
    String slug = "ing-fee-" + tag;
    UUID card = card(issuer, slug, "Fee Card " + tag);
    SITE.put("/fee/" + tag, Page.html(page("Fee Card " + tag, 500, 999, 5, null)));
    UUID src = source(issuer, card, SITE.url("/fee/" + tag), "html");

    JsonNode run = crawl(src);
    assertThat(stat(run, "reviewItems")).as(run.toString()).isEqualTo(1);
    List<JsonNode> pending = reviews(card, "pending");
    assertThat(pending).hasSize(1);
    JsonNode item = pending.getFirst();
    assertThat(item.get("baseVersion").asInt()).isEqualTo(1);
    assertThat(item.get("cardSlug").asString()).isEqualTo(slug);
    assertThat(item.get("url").asString()).isEqualTo(SITE.url("/fee/" + tag));
    JsonNode changes = item.get("changes");
    assertThat(changes.size()).as(changes.toString()).isEqualTo(1);
    assertThat(changes.get(0).get("path").asString()).isEqualTo("annualFeePaise");
    assertThat(changes.get(0).get("from").asLong()).isEqualTo(50_000);
    assertThat(changes.get(0).get("to").asLong()).isEqualTo(99_900);
    assertThat(anon("GET", "/v1/catalog/cards/" + slug).json().get("annualFeePaise").asLong())
        .as("nothing public changes before approve").isEqualTo(50_000);

    String id = item.get("id").asString();
    Res a1 = approve(id);
    assertThat(a1.status()).as(a1.toString()).isEqualTo(200);
    assertThat(a1.json().get("newVersion").asInt()).isEqualTo(2);
    assertThat(a1.json().get("replayed").asBoolean()).isFalse();
    Res a2 = approve(id);
    assertThat(a2.status()).isEqualTo(200);
    assertThat(a2.json().get("newVersion").asInt()).isEqualTo(2);
    assertThat(a2.json().get("replayed").asBoolean()).isTrue();
    assertThat(versions(card)).as("a double approve creates one version").isEqualTo(2);
    JsonNode pub = anon("GET", "/v1/catalog/cards/" + slug).json();
    assertThat(pub.get("version").asInt()).isEqualTo(2);
    assertThat(pub.get("annualFeePaise").asLong()).isEqualTo(99_900);
    assertThat(pub.get("joiningFeePaise").asLong()).isEqualTo(50_000);
    JsonNode decided = as(editor(), "GET", "/v1/admin/review/" + id, null).json();
    assertThat(decided.get("status").asString()).isEqualTo("approved");
    assertThat(decided.get("reviewerId").asString()).isEqualTo(EDITOR.toString());
    assertThat(decided.get("promotion").get("newVersion").asInt()).isEqualTo(2);

    // the same page again: unchanged; then a new fee and two concurrent approves → still one new version
    assertThat(stat(crawl(src), "unchanged")).isEqualTo(1);
    SITE.put("/fee/" + tag, Page.html(page("Fee Card " + tag, 500, 1111, 5, null)));
    crawl(src);
    String id2 = reviews(card, "pending").getFirst().get("id").asString();
    CountDownLatch go = new CountDownLatch(1);
    List<CompletableFuture<Res>> both = List.of(1, 2).stream().map(i -> CompletableFuture.supplyAsync(() -> {
      try {
        go.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return approve(id2);
    })).toList();
    go.countDown();
    List<Res> results = both.stream().map(CompletableFuture::join).toList();
    assertThat(results).allSatisfy(r -> assertThat(r.status()).as(r.toString()).isEqualTo(200));
    assertThat(results).filteredOn(r -> !r.json().get("replayed").asBoolean()).hasSize(1);
    assertThat(results).allSatisfy(r -> assertThat(r.json().get("newVersion").asInt()).isEqualTo(3));
    assertThat(versions(card)).isEqualTo(3);
    assertThat(ADMIN.sql("SELECT count(1) FROM catalog.promotions WHERE card_product_id = :c").param("c", card)
        .query(Long.class).single()).isEqualTo(2);
    assertThat(ADMIN.sql("SELECT count(1) FROM catalog.outbox WHERE message_key = :c AND event_type"
        + " = 'catalog.card.published'").param("c", card.toString()).query(Long.class).single())
        .as("v1 + two promotions").isEqualTo(3);
  }

  @Test
  void newerItemsSupersedeAndStaleOrBlockedApprovesAreRefused() {
    String issuer = newIssuer();
    String tag = Cards.suffix();
    UUID card = card(issuer, "ing-sup-" + tag, "Sup Card " + tag);
    String path = "/sup/" + tag;
    UUID src = source(issuer, card, SITE.url(path), "html");

    SITE.put(path, Page.html(page("Sup Card " + tag, 500, 700, 5, null)));
    crawl(src);
    String a = reviews(card, "pending").getFirst().get("id").asString();
    // different bytes, same facts: no second item
    SITE.put(path, Page.html(page("Sup Card " + tag, 500, 700, 5, "updated footer")));
    JsonNode same = crawl(src);
    assertThat(stat(same, "duplicateDiff")).isEqualTo(1);
    assertThat(reviews(card, "pending")).extracting(i -> i.get("id").asString()).containsExactly(a);
    // new facts: the pending item is superseded
    SITE.put(path, Page.html(page("Sup Card " + tag, 500, 800, 7, null)));
    JsonNode newer = crawl(src);
    assertThat(stat(newer, "superseded")).isEqualTo(1);
    List<JsonNode> pending = reviews(card, "pending");
    assertThat(pending).hasSize(1);
    String b = pending.getFirst().get("id").asString();
    assertThat(pending.getFirst().get("changes")).extracting(c -> c.get("path").asString())
        .containsExactly("annualFeePaise", "rules/food.delivery");
    assertThat(approve(a).code()).isEqualTo("REVIEW_STALE");

    // a draft with unpublished edits blocks the approve until it is discarded
    Res draft = as(editor(), "GET", "/v1/admin/catalog/cards/" + card + "/draft", null);
    Map<String, Object> edited = Cards.doc("ing-sup-" + tag, "Sup Card " + tag + " Edited", issuer, "visa", 50_000);
    assertThat(as(editor(), "PUT", "/v1/admin/catalog/cards/" + card + "/draft", edited, "If-Match",
        draft.header("ETag")).status()).isEqualTo(200);
    assertThat(approve(b).code()).isEqualTo("DRAFT_PENDING");
    assertThat(as(editor(), "DELETE", "/v1/admin/catalog/cards/" + card + "/draft", null).status()).isEqualTo(204);
    Res ok = approve(b);
    assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
    JsonNode pub = anon("GET", "/v1/catalog/cards/ing-sup-" + tag).json();
    assertThat(pub.get("annualFeePaise").asLong()).isEqualTo(80_000);
    assertThat(pub.get("name").asString()).as("the discarded edit is gone").isEqualTo("Sup Card " + tag);
    JsonNode delivery = null;
    for (JsonNode r : pub.get("rules")) {
      JsonNode path = r.get("categoryPath");
      if (path != null && path.isString() && "food.delivery".equals(path.asString()) && r.path("merchantId").isNull()) {
        delivery = r;
      }
    }
    assertThat(delivery).as(pub.toString()).isNotNull();
    assertThat(delivery.get("rateBps").asInt()).isEqualTo(700);
    assertThat(delivery.get("capPaise").asLong()).as("the rule keeps its cap").isEqualTo(100_000);

    // an item made against version 2 goes stale when an editor publishes version 3 first; reject still works
    SITE.put(path, Page.html(page("Sup Card " + tag, 500, 900, 7, null)));
    crawl(src);
    String c = reviews(card, "pending").getFirst().get("id").asString();
    Res d2 = as(editor(), "GET", "/v1/admin/catalog/cards/" + card + "/draft", null);
    Map<String, Object> v3 = Cards.doc("ing-sup-" + tag, "Sup Card " + tag + " Three", issuer, "visa", 50_000);
    as(editor(), "PUT", "/v1/admin/catalog/cards/" + card + "/draft", v3, "If-Match", d2.header("ETag"));
    assertThat(publish(card).status()).isEqualTo(200);
    assertThat(approve(c).code()).isEqualTo("REVIEW_STALE");
    Res rej = as(editor(), "POST", "/v1/admin/review/" + c + ":reject", Map.of("reason", "superseded by edit"));
    assertThat(rej.status()).as(rej.toString()).isEqualTo(200);
    assertThat(rej.json().get("status").asString()).isEqualTo("rejected");
    assertThat(approve(c).code()).isEqualTo("REVIEW_DECIDED");
    assertThat(reviews(card, "rejected")).hasSize(1);
    assertThat(reviews(card, "superseded")).hasSize(1);
  }
}
