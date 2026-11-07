package in.dexter.billing;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.billing.app.SubscriptionService;
import in.dexter.billing.support.BillingIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The period-end job: a subscription set to cancel ends at the end of its period and the account is FREE again; one
 * not set to cancel goes past due (renewal charging is not built, BUILD_README §7), keeps its plan through the grace
 * period, then expires.
 */
class PeriodEndIT extends BillingIT {
  @Autowired
  SubscriptionService subscriptions;

  static void endPeriodAgo(UUID account, String ago) {
    ADMIN.sql("UPDATE billing.subscriptions SET current_period_start = now() - interval '40 days',"
        + " current_period_end = now() - CAST(:ago AS interval) WHERE account_id = :a"
        + " AND status IN ('active', 'past_due')").param("a", account).param("ago", ago).update();
  }

  @Test
  void aCanceledSubscriptionEndsWithItsPeriodAndTheAccountIsFreeAgain() {
    UUID a = UUID.randomUUID();
    subscribe(a);
    assertThat(cancel(a).status()).isEqualTo(200);
    subscriptions.endPeriods(100);
    assertThat(get("/v1/subscriptions/me", a).json().get("status").asString()).as("not yet").isEqualTo("active");
    endPeriodAgo(a, "1 minute");
    assertThat(subscriptions.endPeriods(100)).isGreaterThanOrEqualTo(1);
    JsonNode me = get("/v1/subscriptions/me", a).json();
    assertThat(me.get("status").asString()).isEqualTo("canceled");
    assertThat(me.get("endedAt").isNull()).isFalse();
    List<JsonNode> changed = outbox(a, "billing.subscription.changed");
    assertThat(changed).extracting(n -> n.get("status").asString()).containsExactly("active", "active", "canceled");
    List<JsonNode> ent = outbox(a, "billing.entitlements.changed");
    assertThat(ent).extracting(n -> n.get("tier").asString()).containsExactly("PREMIUM", "FREE");
    assertThat(ent).extracting(n -> n.get("version").asLong()).containsExactly(1L, 2L);
    assertThat(get("/v1/entitlements/me", a).json().get("tier").asString()).isEqualTo("FREE");
    // and a new checkout is allowed again
    assertThat(checkout(a, UUID.randomUUID(), "VIP", "pm_mock_success", "KA").status()).isEqualTo(201);
  }

  @Test
  void anUncanceledPeriodGoesPastDueKeepsThePlanThroughTheGraceThenExpires() {
    UUID a = UUID.randomUUID();
    subscribe(a);
    endPeriodAgo(a, "1 minute");
    subscriptions.endPeriods(100);
    assertThat(get("/v1/subscriptions/me", a).json().get("status").asString()).isEqualTo("past_due");
    assertThat(get("/v1/entitlements/me", a).json().get("tier").asString()).as("inside the grace period")
        .isEqualTo("PREMIUM");
    assertThat(outboxRows(a, "billing.entitlements.changed")).isEqualTo(1);
    subscriptions.endPeriods(100);
    assertThat(get("/v1/subscriptions/me", a).json().get("status").asString()).as("still in grace")
        .isEqualTo("past_due");
    endPeriodAgo(a, "4 days");
    subscriptions.endPeriods(100);
    assertThat(get("/v1/subscriptions/me", a).json().get("status").asString()).isEqualTo("expired");
    assertThat(outbox(a, "billing.subscription.changed")).extracting(n -> n.get("status").asString())
        .containsExactly("active", "past_due", "expired");
    assertThat(get("/v1/entitlements/me", a).json().get("tier").asString()).isEqualTo("FREE");
  }
}
