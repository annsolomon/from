package in.dexter.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.catalog.support.Cards;
import in.dexter.catalog.support.IngestionIT;
import in.dexter.catalog.support.MockIssuerSite.Page;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * /v1/admin/ingestion and /v1/admin/review (MASTER_PLAN P10): source CRUD with If-Match and soft delete, run
 * validation, the keyset review queue, and who may call what (admin token, catalog:read/write/publish).
 */
class IngestionApiIT extends IngestionIT {

  @Test
  void sourcesCrud() {
    String issuer = newIssuer();
    String tag = Cards.suffix();
    UUID card = card(issuer, "ing-api-" + tag, "Api Card " + tag);
    Res created = createSource(issuer, card, SITE.url("/api/" + tag), "html");
    assertThat(created.status()).as(created.toString()).isEqualTo(201);
    assertThat(created.header("ETag")).isEqualTo("\"0\"");
    assertThat(created.header("Location")).isEqualTo("/v1/admin/ingestion/sources/" + created.json().get("id").asString());
    JsonNode s = created.json();
    assertThat(s.get("host").asString()).isEqualTo(SITE.hostPort());
    assertThat(s.get("issuerCode").asString()).isEqualTo(issuer);
    assertThat(s.get("enabled").asBoolean()).isTrue();
    String id = s.get("id").asString();

    assertThat(createSource(issuer, card, SITE.url("/api/" + tag), "html").code()).isEqualTo("SOURCE_EXISTS");
    assertThat(createSource("NO_SUCH_ISSUER", null, SITE.url("/api2/" + tag), "html").code())
        .isEqualTo("VALIDATION_FAILED");
    assertThat(createSource(issuer, card, SITE.url("/api3/" + tag), "sitemap").code()).as("a sitemap names no card")
        .isEqualTo("VALIDATION_FAILED");
    assertThat(createSource(newIssuer(), card, SITE.url("/api4/" + tag), "html").json().get("errors").get(0)
        .get("code").asString()).isEqualTo("OTHER_ISSUER");

    String path = "/v1/admin/ingestion/sources/" + id;
    assertThat(send("PATCH", path, "{\"enabled\":false}", "application/merge-patch+json",
        "Authorization", "Bearer " + editor()).status()).as("If-Match required").isEqualTo(428);
    assertThat(send("PATCH", path, "{\"enabled\":false}", "application/merge-patch+json",
        "Authorization", "Bearer " + editor(), "If-Match", "\"7\"").code()).isEqualTo("VERSION_CONFLICT");
    assertThat(send("PATCH", path, "{\"host\":\"x\"}", "application/merge-patch+json",
        "Authorization", "Bearer " + editor(), "If-Match", "\"0\"").code()).isEqualTo("VALIDATION_FAILED");
    Res patched = send("PATCH", path, "{\"enabled\":false,\"scheduleCron\":\"0 6 * * *\"}",
        "application/merge-patch+json", "Authorization", "Bearer " + editor(), "If-Match", "\"0\"");
    assertThat(patched.status()).as(patched.toString()).isEqualTo(200);
    assertThat(patched.header("ETag")).isEqualTo("\"1\"");
    assertThat(patched.json().get("enabled").asBoolean()).isFalse();
    assertThat(patched.json().get("nextRunAt").isNull()).isFalse();

    assertThat(as(editor(), "POST", "/v1/admin/ingestion/sources/" + id + "/runs", null).json().get("errors").get(0)
        .get("code").asString()).as("a disabled source does not run").isEqualTo("NONE_RUNNABLE");
    assertThat(as(editor(), "POST", "/v1/admin/ingestion/runs", Map.of("sourceIds", List.of())).code())
        .isEqualTo("VALIDATION_FAILED");

    assertThat(as(editor(), "DELETE", path, null).status()).isEqualTo(204);
    assertThat(as(editor(), "GET", path, null).code()).isEqualTo("NOT_FOUND");
    assertThat(createSource(issuer, card, SITE.url("/api/" + tag), "html").status())
        .as("the URL is free again after the delete").isEqualTo(201);
  }

  @Test
  void listsPageWithKeysets() {
    String issuer = newIssuer();
    String tag = Cards.suffix();
    for (int i = 0; i < 5; i++) {
      source(issuer, null, SITE.url("/list/" + tag + "/" + i), "html");
    }
    Set<String> seen = new HashSet<>();
    String cursor = null;
    int pages = 0;
    do {
      Res r = as(editor(), "GET", "/v1/admin/ingestion/sources?limit=2"
          + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)), null);
      assertThat(r.status()).as(r.toString()).isEqualTo(200);
      r.json().get("data").forEach(x -> assertThat(seen.add(x.get("id").asString())).isTrue());
      JsonNode next = r.json().get("page").get("nextCursor");
      cursor = next == null || next.isNull() ? null : next.asString();
      pages++;
    } while (cursor != null && pages < 500);
    assertThat(seen.size()).isGreaterThanOrEqualTo(5);
    assertThat(as(editor(), "GET", "/v1/admin/ingestion/runs?limit=3", null).status()).isEqualTo(200);
    assertThat(as(editor(), "GET", "/v1/admin/review?status=bogus", null).code()).isEqualTo("VALIDATION_FAILED");
    Res q = as(editor(), "GET", "/v1/admin/review?status=pending&limit=1", null);
    assertThat(q.status()).isEqualTo(200);
    String c = q.json().get("page").get("nextCursor").isNull() ? null : q.json().get("page").get("nextCursor").asString();
    if (c != null) {
      assertThat(as(editor(), "GET", "/v1/admin/review?status=approved&limit=1&cursor="
          + URLEncoder.encode(c, StandardCharsets.UTF_8), null).code()).isEqualTo("CURSOR_FILTER_MISMATCH");
    }
  }

  @Test
  void onlyAdminTokensWithCatalogPermissionsGetIn() {
    String issuer = newIssuer();
    String tag = Cards.suffix();
    UUID card = card(issuer, "ing-perm-" + tag, "Perm Card " + tag);
    SITE.put("/perm/" + tag, Page.html(page("Perm Card " + tag, 500, 650, 5, null)));
    UUID src = source(issuer, card, SITE.url("/perm/" + tag), "html");
    crawl(src);
    String item = reviews(card, "pending").getFirst().get("id").asString();

    String reader = editorToken(EDITOR, "catalog:read");
    String writer = editorToken(EDITOR, "catalog:read", "catalog:write");
    String user = token(UUID.randomUUID());
    assertThat(anon("GET", "/v1/admin/review").status()).isEqualTo(401);
    assertThat(as(user, "GET", "/v1/admin/review", null).status()).isEqualTo(403);
    assertThat(as(reader, "GET", "/v1/admin/review", null).status()).isEqualTo(200);
    assertThat(as(reader, "GET", "/v1/admin/ingestion/sources", null).status()).isEqualTo(200);
    assertThat(as(reader, "POST", "/v1/admin/ingestion/runs", null).status()).as("runs need catalog:write")
        .isEqualTo(403);
    assertThat(as(writer, "POST", "/v1/admin/review/" + item + ":approve", null).status())
        .as("approve needs catalog:publish").isEqualTo(403);
    assertThat(as(user, "POST", "/v1/admin/ingestion/sources", Map.of("url", "https://x.issuer.test/")).status())
        .isEqualTo(403);
    assertThat(as(editor(), "POST", "/v1/admin/review/" + item + ":promote", null).code()).isEqualTo("NOT_FOUND");
    assertThat(as(editor(), "POST", "/v1/admin/review/not-an-id:approve", null).code()).isEqualTo("INVALID_ID");
    assertThat(as(editor(), "POST", "/v1/admin/review/" + UUID.randomUUID() + ":approve", null).code())
        .isEqualTo("NOT_FOUND");
    assertThat(approve(item).status()).isEqualTo(200);
  }
}
