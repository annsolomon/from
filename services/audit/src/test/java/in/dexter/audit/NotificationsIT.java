package in.dexter.audit;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.audit.support.AuditIT;
import in.dexter.contracts.events.billing.SubscriptionChangedV1;
import in.dexter.contracts.events.commerce.ApplicationStatusChangedV1;
import in.dexter.contracts.events.identity.SecurityAlertV2;
import in.dexter.contracts.events.planning.BudgetThresholdCrossedV1;
import in.dexter.core.id.Ids;
import in.dexter.events.EventEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Notifications (MASTER_PLAN P18): the four consumers' events become notifications by the rules (channels,
 * preferences, security never muted), the inbox and read marks, preferences with If-Match, and the named test —
 * <b>notification retry with backoff</b>.
 */
class NotificationsIT extends AuditIT {

  @BeforeEach
  void transport() {
    SENDER.failFirst.set(0);
  }

  EventEnvelope budget(UUID account) {
    return envelope(BudgetThresholdCrossedV1.TYPE, 1, "planning", account, new BudgetThresholdCrossedV1(Ids.newId(),
        "Eating out", "monthly", "food.dining", "2026-11-01", 80, 1_000_000L, 812_500L, 8125, Instant.now().toString()));
  }

  List<Map<String, Object>> of(UUID account) {
    return rows("SELECT channel, category, template, status, attempts FROM audit.notifications WHERE account_id = ?"
        + " ORDER BY created_at, channel", account);
  }

  @Test
  void eachEventNotifiesByTheRulesAndOnlyOnce() {
    UUID a = Ids.newId();
    EventEnvelope b = budget(a);
    ingest.onEvent(b);
    ingest.onEvent(b); // a redelivery
    ingest.onEvent(envelope(SubscriptionChangedV1.TYPE, 1, "billing", a, new SubscriptionChangedV1(Ids.newId(),
        "PREMIUM", "active", "2026-11-11T00:00:00Z", "2026-12-11T00:00:00Z", false, 1, Instant.now().toString())));
    ingest.onEvent(envelope(ApplicationStatusChangedV1.TYPE, 1, "commerce", a, new ApplicationStatusChangedV1(
        Ids.newId(), Ids.newId(), "approved", "in_review", "Meets the issuer's criteria", 3,
        Instant.now().toString())));
    ingest.onEvent(envelope(SecurityAlertV2.TYPE, 2, "identity", a, new SecurityAlertV2(SecurityAlertV2.MFA_DISABLED,
        Ids.newId(), Instant.now().toString())));
    List<Map<String, Object>> rows = of(a);
    assertThat(rows).extracting(r -> r.get("template") + ":" + r.get("channel")).containsExactlyInAnyOrder(
        "budget.threshold:email", "budget.threshold:inapp",
        "billing.subscription:email", "billing.subscription:inapp",
        "commerce.application:email", "commerce.application:inapp", "commerce.application:push",
        "security.mfa_disabled:email", "security.mfa_disabled:inapp");
    rows.forEach(r -> assertThat(r.get("status")).as(r.toString())
        .isEqualTo("inapp".equals(r.get("channel")) ? "sent" : "pending"));

    // the inbox: newest first, unread count, rendered texts
    Res inbox = user("GET", "/v1/notifications", a, null);
    assertThat(inbox.status()).as(inbox.toString()).isEqualTo(200);
    assertThat(inbox.json().get("unread").asLong()).isEqualTo(4);
    JsonNode first = inbox.json().get("data").get(0);
    assertThat(first.get("template").asString()).isEqualTo("security.mfa_disabled");
    assertThat(first.get("title").asString()).isEqualTo("Two-factor authentication off");
    JsonNode budgetItem = inbox.json().get("data").get(3);
    assertThat(budgetItem.get("body").asString()).contains("₹8,125.00").contains("₹10,000.00");
    Res read = user("POST", "/v1/notifications/" + first.get("id").asString() + ":read", a, null);
    assertThat(read.status()).as(read.toString()).isEqualTo(200);
    assertThat(read.json().get("readAt").isNull()).isFalse();
    assertThat(user("GET", "/v1/notifications?unread=true", a, null).json().get("data")).hasSize(3);
    // someone else's notification is not found
    assertThat(user("POST", "/v1/notifications/" + first.get("id").asString() + ":read", Ids.newId(), null).code())
        .isEqualTo("NOTIFICATION_NOT_FOUND");
    assertThat(send("GET", "/v1/notifications", null, "Authorization", "Bearer " + operator("audit:read")).code())
        .isEqualTo("USER_TOKEN_REQUIRED");
  }

  @Test
  void preferencesMuteAndSwitchChannelsButNeverSecurity() {
    UUID a = Ids.newId();
    Res prefs = user("GET", "/v1/notification-prefs", a, null);
    assertThat(prefs.json().get("version").asInt()).isZero();
    assertThat(prefs.header("ETag")).isEqualTo("\"0\"");
    assertThat(user("PATCH", "/v1/notification-prefs", a, "{\"muted\":[\"budget\"]}").status())
        .as("If-Match required").isEqualTo(428);
    Res bad = user("PATCH", "/v1/notification-prefs", a, "{\"muted\":[\"security\"]}", "If-Match", "\"0\"");
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.body()).contains("NOT_MUTABLE");
    Res ok = user("PATCH", "/v1/notification-prefs", a, "{\"muted\":[\"budget\"],\"email\":false}", "If-Match",
        "\"0\"");
    assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
    assertThat(ok.json().get("version").asInt()).isEqualTo(1);
    assertThat(user("PATCH", "/v1/notification-prefs", a, "{\"push\":false}", "If-Match", "\"0\"").status())
        .as("stale").isEqualTo(412);

    ingest.onEvent(budget(a));
    ingest.onEvent(envelope(SecurityAlertV2.TYPE, 2, "identity", a, new SecurityAlertV2(
        SecurityAlertV2.ACCOUNT_LOCKED, Ids.newId(), Instant.now().toString())));
    ingest.onEvent(envelope(ApplicationStatusChangedV1.TYPE, 1, "commerce", a, new ApplicationStatusChangedV1(
        Ids.newId(), Ids.newId(), "rejected", "in_review", null, 3, Instant.now().toString())));
    assertThat(of(a)).extracting(r -> r.get("template") + ":" + r.get("channel")).containsExactlyInAnyOrder(
        "security.account_locked:inapp", "security.account_locked:email",
        "commerce.application:inapp", "commerce.application:push");
  }

  /** The named test. */
  @Test
  void notificationRetryWithBackoff() throws Exception {
    UUID a = Ids.newId();
    SENDER.failFirst.set(2);
    ingest.onEvent(budget(a));
    UUID email = ADMIN.sql("SELECT id FROM audit.notifications WHERE account_id = ? AND channel = 'email'")
        .param(1, a).query(UUID.class).single();
    for (int attempt = 1; attempt <= 3; attempt++) {
      awaitDue(email);
      notifications.deliverDue(50);
      Map<String, Object> row = row(email);
      assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(attempt);
      if (attempt < 3) {
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("last_error").toString()).contains("scripted failure of attempt " + attempt);
        // backoff: 200 ms after the first failure, 400 ms after the second (base · 2^(n-1))
        Instant failedAt = SENDER.of(email).getLast().at();
        Instant next = Instant.ofEpochMilli(((Number) row.get("next_ms")).longValue());
        long waitMs = Duration.between(failedAt, next).toMillis();
        long expected = 200L << (attempt - 1);
        assertThat(waitMs).as("attempt " + attempt).isBetween(expected - 100, expected + 400);
      } else {
        assertThat(row.get("status")).isEqualTo("sent");
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("last_error")).isNull();
      }
    }
    assertThat(SENDER.of(email)).extracting(c -> c.attempt() + ":" + c.ok()).containsExactly("1:false", "2:false",
        "3:true");

    // a transport that never recovers: the last allowed attempt (4) fails it for good, with growing gaps
    SENDER.failFirst.set(100);
    UUID b = Ids.newId();
    ingest.onEvent(budget(b));
    UUID doomed = ADMIN.sql("SELECT id FROM audit.notifications WHERE account_id = ? AND channel = 'email'")
        .param(1, b).query(UUID.class).single();
    for (int attempt = 1; attempt <= 4; attempt++) {
      awaitDue(doomed);
      notifications.deliverDue(50);
    }
    Map<String, Object> row = row(doomed);
    assertThat(row.get("status")).isEqualTo("failed");
    assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(4);
    assertThat(row.get("next_attempt_at")).isNull();
    List<Instant> at = SENDER.of(doomed).stream().map(c -> c.at()).toList();
    assertThat(at).hasSize(4);
    for (int i = 1; i < at.size(); i++) {
      assertThat(Duration.between(at.get(i - 1), at.get(i)).toMillis()).as("gap " + i)
          .isGreaterThanOrEqualTo((200L << (i - 1)) - 50);
    }
    notifications.deliverDue(50);
    assertThat(SENDER.of(doomed)).as("a failed notification is not retried").hasSize(4);
  }

  Map<String, Object> row(UUID id) {
    return rows("SELECT status, attempts, next_attempt_at, (extract(epoch FROM next_attempt_at) * 1000)::bigint"
        + " AS next_ms, last_error FROM audit.notifications WHERE id = ?", id)
        .getFirst();
  }

  void awaitDue(UUID id) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (count("SELECT count(1) FROM audit.notifications WHERE id = ? AND status = 'pending'"
        + " AND next_attempt_at <= now()", id) == 0) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("notification " + id + " never became due");
      }
      Thread.sleep(25);
    }
  }
}
