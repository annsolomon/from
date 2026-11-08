package in.dexter.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.commerce.support.CommerceIT;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Card applications (MASTER_PLAN P17): apply against the catalog projection, one open application per card (the
 * named "duplicate application" test, including a race with different keys), withdraw, the operators' queue and
 * decision with If-Match, and an event for every status change.
 */
class CardApplicationsIT extends CommerceIT {
  static final String EVENT = "commerce.application.status-changed";

  @BeforeEach
  void cards() {
    ensureCards();
  }

  Res apply(UUID account, UUID card) {
    return apply(account, card, UUID.randomUUID());
  }

  Res apply(UUID account, UUID card, UUID key) {
    return send("POST", "/v1/card-applications", "{\"cardProductId\":\"" + card + "\"}",
        auth(account, "Idempotency-Key", key.toString()));
  }

  @Test
  void onlyPublishedCardsMayBeAppliedFor() {
    UUID a = UUID.randomUUID();
    Res unknown = apply(a, UUID.randomUUID());
    assertThat(unknown.status()).as(unknown.toString()).isEqualTo(400);
    assertThat(unknown.body()).contains("UNKNOWN_CARD_PRODUCT");
    assertThat(apply(a, RETIRED_CARD).body()).as("a retired card").contains("UNKNOWN_CARD_PRODUCT");
    assertThat(send("POST", "/v1/card-applications", "{}", auth(a, "Idempotency-Key", UUID.randomUUID().toString()))
        .status()).isEqualTo(400);
    Res noKey = send("POST", "/v1/card-applications", "{\"cardProductId\":\"" + CARD_A + "\"}", auth(a));
    assertThat(noKey.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    Res adminToken = send("POST", "/v1/card-applications", "{\"cardProductId\":\"" + CARD_A + "\"}",
        authWith(operator(), "Idempotency-Key", UUID.randomUUID().toString()));
    assertThat(adminToken.code()).isEqualTo("USER_TOKEN_REQUIRED");
  }

  /** MASTER_PLAN P17 named test: duplicate application. */
  @Test
  void aSecondOpenApplicationForTheSameCardIsRefused() throws Exception {
    UUID a = UUID.randomUUID();
    Res first = apply(a, CARD_A);
    assertThat(first.status()).as(first.toString()).isEqualTo(201);
    assertThat(first.json().get("status").asString()).isEqualTo("submitted");
    assertThat(first.header("ETag")).isEqualTo("\"1\"");
    Res dup = apply(a, CARD_A);
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.code()).isEqualTo("APPLICATION_EXISTS");
    assertThat(apply(a, CARD_B).status()).as("another card is fine").isEqualTo(201);

    // a race of ten different keys for the same card: the partial unique index lets exactly one through
    UUID racer = UUID.randomUUID();
    ExecutorService pool = Executors.newFixedThreadPool(10);
    List<Future<Res>> fs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      fs.add(pool.submit(() -> apply(racer, CARD_A)));
    }
    int created = 0;
    for (Future<Res> f : fs) {
      Res r = f.get();
      if (r.status() == 201) {
        created++;
      } else {
        assertThat(r.code()).as(r.toString()).isEqualTo("APPLICATION_EXISTS");
      }
    }
    pool.shutdown();
    assertThat(created).isEqualTo(1);
    assertThat(count("SELECT count(1) FROM commerce.card_applications WHERE account_id = ? AND status = 'submitted'",
        racer)).isEqualTo(1);
    assertThat(outboxRows(racer, EVENT)).isEqualTo(1);

    // withdrawn, the card may be applied for again
    String id = first.json().get("id").asString();
    Res w = send("POST", "/v1/card-applications/" + id + ":withdraw", null, auth(a));
    assertThat(w.status()).as(w.toString()).isEqualTo(200);
    assertThat(w.json().get("status").asString()).isEqualTo("withdrawn");
    assertThat(send("POST", "/v1/card-applications/" + id + ":withdraw", null, auth(a)).code())
        .isEqualTo("APPLICATION_NOT_OPEN");
    assertThat(apply(a, CARD_A).status()).isEqualTo(201);
    List<JsonNode> events = outbox(a, EVENT);
    assertThat(events).extracting(e -> e.get("status").asString())
        .containsExactly("submitted", "submitted", "withdrawn", "submitted");
  }

  @Test
  void applicationsAreTheirAccountsOwn() {
    UUID a = UUID.randomUUID();
    String id = apply(a, CARD_A).json().get("id").asString();
    assertThat(get("/v1/card-applications/" + id, a).status()).isEqualTo(200);
    assertThat(get("/v1/card-applications/" + id, UUID.randomUUID()).code()).isEqualTo("APPLICATION_NOT_FOUND");
    Res list = get("/v1/card-applications", a);
    assertThat(list.json().get("data")).hasSize(1);
    assertThat(list.json().get("data").get(0).get("history").get(0).get("status").asString()).isEqualTo("submitted");
    assertThat(get("/v1/card-applications", null).status()).isEqualTo(401);
  }

  @Test
  void operatorsReviewAndDecideWithIfMatch() {
    UUID a = UUID.randomUUID();
    Res applied = apply(a, CARD_B);
    String id = applied.json().get("id").asString();
    String path = "/v1/admin/commerce/card-applications/" + id;

    Res queue = admin("GET", "/v1/admin/commerce/card-applications?limit=100", null);
    assertThat(queue.status()).as(queue.toString()).isEqualTo(200);
    Res one = admin("GET", path, null);
    assertThat(one.json().get("accountId").asString()).isEqualTo(a.toString());

    Res stale = admin("POST", path + ":decide", "{\"decision\":\"in_review\"}", "If-Match", "\"9\"");
    assertThat(stale.status()).isEqualTo(412);
    assertThat(admin("POST", path + ":decide", "{\"decision\":\"in_review\"}").status()).as("If-Match required")
        .isEqualTo(428);
    Res review = admin("POST", path + ":decide", "{\"decision\":\"in_review\"}", "If-Match", "\"1\"");
    assertThat(review.status()).as(review.toString()).isEqualTo(200);
    assertThat(review.json().get("status").asString()).isEqualTo("in_review");
    assertThat(review.header("ETag")).isEqualTo("\"2\"");
    Res noReason = admin("POST", path + ":decide", "{\"decision\":\"rejected\"}", "If-Match", "\"2\"");
    assertThat(noReason.status()).as("a rejection needs a reason").isEqualTo(400);
    Res approve = admin("POST", path + ":decide", "{\"decision\":\"approved\",\"reason\":\"Meets criteria\"}",
        "If-Match", "\"2\"");
    assertThat(approve.status()).as(approve.toString()).isEqualTo(200);
    assertThat(approve.json().get("decidedAt").isNull()).isFalse();
    assertThat(approve.json().get("history")).hasSize(3);
    Res again = admin("POST", path + ":decide", "{\"decision\":\"rejected\",\"reason\":\"x\"}", "If-Match", "\"3\"");
    assertThat(again.code()).isEqualTo("APPLICATION_TRANSITION_INVALID");
    assertThat(send("POST", "/v1/card-applications/" + id + ":withdraw", null, auth(a)).code())
        .isEqualTo("APPLICATION_NOT_OPEN");

    List<JsonNode> events = outbox(a, EVENT);
    assertThat(events).extracting(e -> e.get("status").asString()).containsExactly("submitted", "in_review",
        "approved");
    assertThat(events.get(2).get("previousStatus").asString()).isEqualTo("in_review");
    assertThat(events.get(2).get("reason").asString()).isEqualTo("Meets criteria");
    assertThat(events.get(2).get("version").asLong()).isEqualTo(3);

    // permissions: deciding needs commerce:decide; the user may not decide at all
    String readOnly = operator(UUID.randomUUID(), List.of("CATALOG_EDITOR"), "commerce:read", "commerce:write");
    assertThat(send("POST", path + ":decide", "{\"decision\":\"approved\"}", authWith(readOnly, "If-Match", "\"3\""))
        .status()).isEqualTo(403);
    assertThat(send("GET", "/v1/admin/commerce/card-applications", null, auth(a)).status()).isEqualTo(403);
  }
}
