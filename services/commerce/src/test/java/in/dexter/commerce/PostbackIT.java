package in.dexter.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.commerce.support.CommerceIT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Affiliate postbacks (MASTER_PLAN P17): signed, idempotent by (network, ref), attributed through the click id the
 * redirect appended; the deal's counters gain the conversion once and the commission when it is approved. And the
 * operators' stats read those counters.
 */
class PostbackIT extends CommerceIT {

  /** A logged click on a fresh deal: [dealId, clickId, viewer]. */
  UUID[] clicked() {
    UUID deal = deal("Converts", "pb" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
        List.of(), 1_000_000);
    UUID viewer = UUID.randomUUID();
    Res r = send("GET", clickPath(deal, viewer), null, "User-Agent", "pb-agent");
    assertThat(r.status()).as(r.toString()).isEqualTo(302);
    String loc = r.header("Location");
    return new UUID[] {deal, UUID.fromString(loc.substring(loc.indexOf("subid=") + 6)), viewer};
  }

  static String body(String ref, UUID subid, String status, long amount, long commission) {
    return "{\"ref\":\"" + ref + "\",\"subid\":" + (subid == null ? "null" : "\"" + subid + "\"") + ",\"status\":\""
        + status + "\",\"amountPaise\":" + amount + ",\"commissionPaise\":" + commission
        + ",\"occurredAt\":\"" + Instant.now() + "\"}";
  }

  long counter(UUID deal, String column) {
    return count("SELECT coalesce(sum(" + column + "), 0) FROM commerce.deal_click_counters WHERE deal_id = ?", deal);
  }

  /** MASTER_PLAN P17 named test: postback replay. */
  @Test
  void aReplayedPostbackChangesNothing() {
    UUID[] c = clicked();
    String ref = "ord-" + UUID.randomUUID();
    String body = body(ref, c[1], "pending", 250_000, 10_000);
    Res first = postback(body);
    assertThat(first.status()).as(first.toString()).isEqualTo(200);
    assertThat(first.json().get("outcome").asString()).isEqualTo("created");
    for (int i = 0; i < 3; i++) {
      Res again = postback(body);
      assertThat(again.json().get("outcome").asString()).isEqualTo("duplicate");
      assertThat(again.json().get("conversionId").asString()).isEqualTo(first.json().get("conversionId").asString());
    }
    assertThat(count("SELECT count(1) FROM commerce.conversions WHERE network = 'mock' AND provider_ref = ?", ref))
        .isEqualTo(1);
    assertThat(ADMIN.sql("SELECT deal_id || '/' || account_id FROM commerce.conversions WHERE provider_ref = :r")
        .param("r", ref).query(String.class).single()).isEqualTo(c[0] + "/" + c[2]);
    assertThat(counter(c[0], "conversions")).isEqualTo(1);
    assertThat(counter(c[0], "commission_paise")).as("pending earns nothing yet").isZero();

    Res approved = postback(body(ref, c[1], "approved", 250_000, 10_000));
    assertThat(approved.json().get("outcome").asString()).isEqualTo("updated");
    assertThat(postback(body(ref, c[1], "approved", 250_000, 10_000)).json().get("outcome").asString())
        .isEqualTo("duplicate");
    assertThat(postback(body(ref, c[1], "rejected", 250_000, 10_000)).json().get("outcome").asString())
        .as("decided is final").isEqualTo("duplicate");
    assertThat(counter(c[0], "conversions")).isEqualTo(1);
    assertThat(counter(c[0], "commission_paise")).isEqualTo(10_000);
    assertThat(ADMIN.sql("SELECT status FROM commerce.conversions WHERE provider_ref = :r").param("r", ref)
        .query(String.class).single()).isEqualTo("approved");
  }

  @Test
  void concurrentDeliveriesOfOnePostbackCreateOneConversion() throws Exception {
    UUID[] c = clicked();
    String ref = "race-" + UUID.randomUUID();
    String body = body(ref, c[1], "approved", 100_000, 5_000);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
    List<java.util.concurrent.Future<Res>> fs = new java.util.ArrayList<>();
    for (int i = 0; i < 8; i++) {
      fs.add(pool.submit(() -> postback(body)));
    }
    int created = 0;
    for (var f : fs) {
      Res r = f.get();
      assertThat(r.status()).as(r.toString()).isEqualTo(200);
      created += r.json().get("outcome").asString().equals("created") ? 1 : 0;
    }
    pool.shutdown();
    assertThat(created).isEqualTo(1);
    assertThat(counter(c[0], "conversions")).isEqualTo(1);
    assertThat(counter(c[0], "commission_paise")).isEqualTo(5_000);
  }

  @Test
  void signatureNetworkAndPayloadAreChecked() {
    String body = body("sig-" + UUID.randomUUID(), null, "pending", 1, 0);
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    Res unsigned = sendBytes("POST", "/v1/webhooks/affiliate/mock", b);
    assertThat(unsigned.code()).isEqualTo("POSTBACK_SIGNATURE_INVALID");
    Res tampered = sendBytes("POST", "/v1/webhooks/affiliate/mock", body.replace("pending", "approved")
        .getBytes(StandardCharsets.UTF_8), "Postback-Signature", postbackSignature(b, Instant.now()));
    assertThat(tampered.status()).isEqualTo(400);
    Res stale = sendBytes("POST", "/v1/webhooks/affiliate/mock", b, "Postback-Signature",
        postbackSignature(b, Instant.now().minusSeconds(600)));
    assertThat(stale.code()).isEqualTo("POSTBACK_SIGNATURE_INVALID");
    Res unknown = sendBytes("POST", "/v1/webhooks/affiliate/nobody", b, "Postback-Signature",
        postbackSignature(b, Instant.now()));
    assertThat(unknown.status()).isEqualTo(404);
    assertThat(unknown.code()).isEqualTo("NETWORK_UNKNOWN");
    Res invalid = postback("{\"ref\":\"bad ref\",\"status\":\"maybe\",\"amountPaise\":-1,\"occurredAt\":\"x\"}");
    assertThat(invalid.status()).as(invalid.toString()).isEqualTo(400);
    assertThat(invalid.body()).contains("ref").contains("status").contains("amountPaise").contains("occurredAt");
  }

  @Test
  void anUnattributablePostbackIsStillKept() {
    String ref = "orphan-" + UUID.randomUUID();
    Res r = postback(body(ref, UUID.randomUUID(), "pending", 5_000, 100));
    assertThat(r.json().get("outcome").asString()).isEqualTo("created");
    assertThat(ADMIN.sql("SELECT coalesce(click_id::text, 'none') FROM commerce.conversions WHERE provider_ref = :r")
        .param("r", ref).query(String.class).single()).isEqualTo("none");
  }

  @Test
  void statsReadTheMaintainedCounters() {
    UUID[] c = clicked();
    postback(body("st-" + UUID.randomUUID(), c[1], "approved", 300_000, 12_000));
    flusher.flush();
    Res s = admin("GET", "/v1/admin/commerce/stats", null);
    assertThat(s.status()).as(s.toString()).isEqualTo(200);
    JsonNode row = null;
    for (JsonNode d : s.json().get("deals")) {
      if (d.get("dealId").asString().equals(c[0].toString())) {
        row = d;
      }
    }
    assertThat(row).as("the deal is in the stats").isNotNull();
    assertThat(row.get("clicks").asLong()).isEqualTo(1);
    assertThat(row.get("dailyUniques").asLong()).isEqualTo(1);
    assertThat(row.get("conversions").asLong()).isEqualTo(1);
    assertThat(row.get("commissionPaise").asLong()).isEqualTo(12_000);
    assertThat(admin("GET", "/v1/admin/commerce/stats?from=2026-01-01&to=2026-12-31", null).code())
        .isEqualTo("STATS_RANGE_INVALID");
    assertThat(send("GET", "/v1/admin/commerce/stats", null, auth(UUID.randomUUID())).status()).isEqualTo(403);
  }
}
