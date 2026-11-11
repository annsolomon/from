package in.dexter.audit;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.audit.support.AuditIT;
import in.dexter.contracts.events.planning.BudgetThresholdCrossedV1;
import in.dexter.contracts.events.privacy.PrivacyCompletedV1;
import in.dexter.core.id.Ids;
import in.dexter.events.EventEnvelope;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The privacy saga's orchestrator (MASTER_PLAN P18, ADR-0024) with two participants whose answers the tests write:
 * an export end to end (step-up, Idempotency-Key, every part zipped with audit's own, the 24-hour link, the in-app
 * notice), and a deletion through every phase including timeouts that re-ask only the silent services, a failure
 * after the last attempt, an operator's resume, and the pseudonymous record at the end. The saga across the real
 * services is {@link PrivacySagaAcrossServicesIT}.
 */
class PrivacyIT extends AuditIT {

  Res request(UUID account, String kind, UUID key) {
    return user("POST", "/v1/privacy/" + kind, account, null, "Idempotency-Key", key.toString());
  }

  /** The services asked in the outbox for this request, phase and attempt (one event each time). */
  List<String> asked(UUID request, String phase, int attempt) {
    List<String> out = new ArrayList<>();
    rows("SELECT payload::text AS p FROM audit.outbox WHERE event_type = 'audit.privacy.requested'"
        + " AND payload->'data'->>'requestId' = ? AND payload->'data'->>'phase' = ?"
        + " AND (payload->'data'->>'attempt')::int = ?", request.toString(), phase, attempt)
        .forEach(r -> JSON.readTree(r.get("p").toString()).get("data").get("services")
            .forEach(s -> out.add(s.asString())));
    return out;
  }

  EventEnvelope ack(UUID account, UUID request, String service, String phase, int attempt, long rows, long remaining,
      String partKey) {
    return new EventEnvelope(Ids.newId(), PrivacyCompletedV1.type(service), 1, service, Instant.now(), account,
        request, attempt, null, JSON.valueToTree(new PrivacyCompletedV1(request, service, phase, attempt, rows,
            remaining, partKey, Instant.now().toString())));
  }

  Map<String, Object> state(UUID request) {
    return rows("SELECT status, phase, attempt, account_id, subject_ref, error FROM audit.privacy_requests"
        + " WHERE id = ?", request).getFirst();
  }

  void timeout() throws InterruptedException {
    Thread.sleep(1_100); // the phase timeout is 1 s in these tests
  }

  @Test
  void anExportZipsEveryServicesPartWithAuditsOwn() throws Exception {
    UUID a = Ids.newId();
    ingest.onEvent(envelope(BudgetThresholdCrossedV1.TYPE, 1, "planning", a, new BudgetThresholdCrossedV1(
        Ids.newId(), "Groceries", "monthly", "food", "2026-11-01", 50, 500_000L, 250_000L, 5000,
        Instant.now().toString())));
    UUID key = Ids.newId();
    // step-up: a sign-in older than ten minutes is not enough
    Res stale = send("POST", "/v1/privacy/export", null, "Authorization", "Bearer " + IDP.user(a).audience("audit")
        .authTime(Instant.now().minusSeconds(3600)).sign(), "Idempotency-Key", key.toString());
    assertThat(stale.status()).as(stale.toString()).isEqualTo(401);
    assertThat(stale.code()).isEqualTo("STEP_UP_REQUIRED");
    Res noKey = user("POST", "/v1/privacy/export", a, null);
    assertThat(noKey.status()).as(noKey.toString()).isBetween(400, 428);

    Res started = request(a, "export", key);
    assertThat(started.status()).as(started.toString()).isEqualTo(202);
    UUID id = UUID.fromString(started.json().get("id").asString());
    assertThat(started.json().get("phase").asString()).isEqualTo("export");
    assertThat(request(a, "export", key).json().get("id").asString()).as("replayed").isEqualTo(id.toString());
    assertThat(request(a, "export", Ids.newId()).code()).isEqualTo("PRIVACY_REQUEST_RUNNING");
    assertThat(asked(id, "export", 1)).containsExactly("identity", "ledger");

    // the parts arrive; a redelivered acknowledgement changes nothing
    STORE.put("privacy/" + id + "/identity.json", "{\"accounts\":[{\"tz\":\"Asia/Kolkata\"}],\"sessions\":[]}"
        .getBytes(StandardCharsets.UTF_8), "application/json");
    STORE.put("privacy/" + id + "/ledger.json", "{\"expenses\":[{\"amount_paise\":100},{\"amount_paise\":200}]}"
        .getBytes(StandardCharsets.UTF_8), "application/json");
    EventEnvelope identity = ack(a, id, "identity", "export", 1, 1, 9, "privacy/" + id + "/identity.json");
    privacy.onAck(identity);
    privacy.onAck(identity);
    assertThat(state(id).get("phase")).isEqualTo("export");
    privacy.onAck(ack(a, id, "ledger", "export", 1, 2, 2, "privacy/" + id + "/ledger.json"));
    assertThat(state(id).get("phase")).isEqualTo("archive");
    privacy.tick(20);
    assertThat(state(id).get("status")).isEqualTo("completed");

    Map<String, String> files = unzip(STORE.objects.get("privacy/" + id + "/export.zip"));
    assertThat(files).containsOnlyKeys("identity.json", "ledger.json", "audit.json", "manifest.json");
    assertThat(JSON.readTree(files.get("ledger.json")).get("expenses")).hasSize(2);
    JsonNode own = JSON.readTree(files.get("audit.json"));
    assertThat(own.get("notifications")).as("audit's own part: the budget notifications").isNotEmpty();
    assertThat(JSON.readTree(files.get("manifest.json")).get("files")).hasSize(3);

    Res done = user("GET", "/v1/privacy/requests/" + id, a, null);
    assertThat(done.json().get("status").asString()).isEqualTo("completed");
    assertThat(done.json().get("downloadUrl").asString()).contains("export.zip").contains("X-Amz-Expires=");
    long ttl = Long.parseLong(done.json().get("downloadUrl").asString().replaceAll(".*X-Amz-Expires=", ""));
    assertThat(ttl).isBetween(86_000L, 86_400L);
    assertThat(done.json().get("steps").get("export").get("ledger").get("rows").asLong()).isEqualTo(2);
    assertThat(count("SELECT count(1) FROM audit.notifications WHERE account_id = ? AND template ="
        + " 'privacy.export-ready' AND channel = 'inapp'", a)).isEqualTo(1);
    assertThat(user("GET", "/v1/privacy/requests", a, null).json().get("data")).hasSize(1);
    assertThat(user("GET", "/v1/privacy/requests/" + id, Ids.newId(), null).code())
        .isEqualTo("PRIVACY_REQUEST_NOT_FOUND");
    assertThat(count("SELECT count(1) FROM audit.audit_log WHERE target_id = ? AND action IN"
        + " ('privacy.export.requested', 'privacy.export.completed')", id.toString())).isEqualTo(2);
  }

  @Test
  void aDeletionTimesOutReAsksTheSilentFailsResumesAndEndsPseudonymous() throws Exception {
    UUID a = Ids.newId();
    ingest.onEvent(envelope(BudgetThresholdCrossedV1.TYPE, 1, "planning", a, new BudgetThresholdCrossedV1(
        Ids.newId(), "Fuel", "monthly", "transport", "2026-11-01", 80, 300_000L, 250_000L, 8333,
        Instant.now().toString())));
    UUID id = UUID.fromString(request(a, "delete", Ids.newId()).json().get("id").asString());
    assertThat(asked(id, "freeze", 1)).containsExactly("identity");

    // freeze: identity is silent once, then answers the second ask
    timeout();
    privacy.tick(20);
    assertThat(state(id).get("attempt")).isEqualTo(2);
    assertThat(asked(id, "freeze", 2)).containsExactly("identity");
    privacy.onAck(ack(a, id, "identity", "freeze", 2, 1, 7, null));
    assertThat(state(id).get("phase")).isEqualTo("erase");
    assertThat(asked(id, "erase", 1)).containsExactly("identity", "ledger");

    // erase: identity answers (its account row and key go at shred); ledger answers but left rows, so it is asked
    // again — alone — until the attempts run out
    privacy.onAck(ack(a, id, "identity", "erase", 1, 6, 2, null));
    privacy.onAck(ack(a, id, "ledger", "erase", 1, 4, 3, null));
    for (int attempt = 2; attempt <= 3; attempt++) {
      timeout();
      privacy.tick(20);
      assertThat(asked(id, "erase", attempt)).as("attempt " + attempt).containsExactly("ledger");
    }
    timeout();
    privacy.tick(20);
    Map<String, Object> failed = state(id);
    assertThat(failed.get("status")).isEqualTo("failed");
    assertThat(failed.get("error").toString()).contains("no answer to erase from ledger after 3 attempts");

    // an operator resumes it (SUPER_ADMIN: audit:verify); SUPPORT may only look
    assertThat(send("POST", "/v1/admin/audit/privacy-requests/" + id + ":resume", null, "Authorization",
        "Bearer " + operator("audit:read")).status()).isEqualTo(403);
    Res resumed = send("POST", "/v1/admin/audit/privacy-requests/" + id + ":resume", null, "Authorization",
        "Bearer " + superAdmin());
    assertThat(resumed.status()).as(resumed.toString()).isEqualTo(200);
    assertThat(resumed.json().get("status").asString()).isEqualTo("running");
    assertThat(asked(id, "erase", 1)).as("asked again from attempt 1").containsExactly("identity", "ledger",
        "ledger");
    assertThat(send("POST", "/v1/admin/audit/privacy-requests/" + id + ":resume", null, "Authorization",
        "Bearer " + superAdmin()).code()).isEqualTo("PRIVACY_REQUEST_NOT_FAILED");
    privacy.onAck(ack(a, id, "ledger", "erase", 1, 3, 0, null));
    assertThat(state(id).get("phase")).isEqualTo("shred");
    assertThat(asked(id, "shred", 1)).containsExactly("identity");
    privacy.onAck(ack(a, id, "identity", "shred", 1, 1, 0, null));
    assertThat(state(id).get("phase")).isEqualTo("local");
    privacy.tick(20);

    Map<String, Object> done = state(id);
    assertThat(done.get("status")).isEqualTo("completed");
    assertThat(done.get("account_id")).as("pseudonymized").isNull();
    assertThat(done.get("subject_ref").toString()).hasSize(64);
    assertThat(count("SELECT count(1) FROM audit.notifications WHERE account_id = ?", a)).isZero();
    assertThat(count("SELECT count(1) FROM audit.privacy_requests WHERE account_id = ?", a)).isZero();
    assertThat(count("SELECT audit.privacy_remaining(?)", a)).isZero();
    assertThat(count("SELECT count(1) FROM audit.audit_log WHERE action = 'privacy.delete.completed'"
        + " AND target_type = 'subject' AND target_id = ?", done.get("subject_ref").toString())).isEqualTo(1);
    Res admin = send("GET", "/v1/admin/audit/privacy-requests/" + id, null, "Authorization",
        "Bearer " + operator("audit:read"));
    assertThat(admin.json().get("accountId").isNull()).isTrue();
    assertThat(admin.json().get("steps").get("erase").get("ledger").get("remaining").asLong()).isZero();
    assertThat(user("GET", "/v1/privacy/requests/" + id, a, null).code()).isEqualTo("PRIVACY_REQUEST_NOT_FOUND");
  }

  static Map<String, String> unzip(byte[] zip) throws Exception {
    Map<String, String> out = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      ZipEntry e;
      while ((e = in.getNextEntry()) != null) {
        out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return out;
  }
}
