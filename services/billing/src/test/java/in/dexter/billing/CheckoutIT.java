package in.dexter.billing;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.billing.support.BillingIT;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * POST /v1/subscriptions/checkout (MASTER_PLAN P16) through every outcome the mock provider has: succeeded (201, the
 * subscription active with its first period, an invoice, both events), declined (402, nothing charged, a second try
 * allowed), and delayed (202 pending until the provider's webhook decides it). Plus what the endpoint refuses.
 */
class CheckoutIT extends BillingIT {

  @Test
  void aSuccessfulCheckoutStartsTheSubscriptionInvoicesItAndAnnouncesIt() {
    UUID a = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    Res r = checkout(a, key, "PREMIUM", "pm_mock_success", "KA");
    assertThat(r.status()).as(r.toString()).isEqualTo(201);
    assertThat(r.header("Location")).isEqualTo("/v1/subscriptions/me");
    JsonNode b = r.json();
    assertThat(b.get("outcome").asString()).isEqualTo("succeeded");
    JsonNode s = b.get("subscription");
    assertThat(s.get("status").asString()).isEqualTo("active");
    assertThat(s.get("planCode").asString()).isEqualTo("PREMIUM");
    Instant start = Instant.parse(s.get("currentPeriodStart").asString());
    Instant end = Instant.parse(s.get("currentPeriodEnd").asString());
    assertThat(Duration.between(start, end).toDays()).isBetween(28L, 31L);
    JsonNode p = b.get("payment");
    assertThat(p.get("status").asString()).isEqualTo("succeeded");
    assertThat(p.get("amountPaise").asLong()).as("price + 18 % GST").isEqualTo(23_482);
    assertThat(p.get("providerPaymentId").asString()).startsWith("pay_mock_");
    assertThat(b.get("invoiceId").isNull()).isFalse();

    List<JsonNode> changed = outbox(a, "billing.subscription.changed");
    assertThat(changed).hasSize(1);
    assertThat(changed.getFirst().get("status").asString()).isEqualTo("active");
    List<JsonNode> ent = outbox(a, "billing.entitlements.changed");
    assertThat(ent).hasSize(1);
    assertThat(ent.getFirst().get("tier").asString()).isEqualTo("PREMIUM");
    assertThat(ent.getFirst().get("version").asLong()).isEqualTo(1);
    assertThat(ent.getFirst().get("entitlements").get("adFree").asBoolean()).isTrue();

    Res me = get("/v1/subscriptions/me", a);
    assertThat(me.status()).isEqualTo(200);
    assertThat(me.json().get("status").asString()).isEqualTo("active");
    assertThat(me.header("ETag")).isEqualTo("\"" + me.json().get("version").asInt() + "\"");
    // the card data Dexter keeps is the provider's token, never a number
    assertThat(one("SELECT payment_method FROM billing.payments WHERE account_id = ?", a)).isEqualTo("pm_mock_success");
  }

  @Test
  void aDeclineChargesNothingStartsNothingAndAllowsAnotherTry() {
    UUID a = UUID.randomUUID();
    Res r = checkout(a, UUID.randomUUID(), "PREMIUM", "pm_mock_decline", "KA");
    assertThat(r.status()).as(r.toString()).isEqualTo(402);
    assertThat(r.json().get("code").asString()).isEqualTo("PAYMENT_DECLINED");
    assertThat(r.json().get("payment").get("failureCode").asString()).isEqualTo("card_declined");
    assertThat(r.json().get("subscription").get("status").asString()).isEqualTo("incomplete_expired");
    assertThat(outboxRows(a, "billing.subscription.changed")).isZero();
    assertThat(outboxRows(a, "billing.entitlements.changed")).as("FREE stays FREE: nothing to announce").isZero();
    assertThat(count("SELECT count(1) FROM billing.invoices WHERE account_id = ?", a)).isZero();
    // the failed attempt is not live, so a second one may start
    Res again = checkout(a, UUID.randomUUID(), "PREMIUM", "pm_mock_success", "KA");
    assertThat(again.status()).as(again.toString()).isEqualTo(201);
    assertThat(get("/v1/subscriptions/me", a).json().get("status").asString()).isEqualTo("active");
  }

  @Test
  void aDelayedPaymentIsPendingUntilTheProvidersWebhookDecidesIt() {
    UUID a = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    Res r = checkout(a, key, "VIP", "pm_mock_delayed", "MH");
    assertThat(r.status()).as(r.toString()).isEqualTo(202);
    assertThat(r.json().get("outcome").asString()).isEqualTo("pending");
    assertThat(r.json().get("subscription").get("status").asString()).isEqualTo("incomplete");
    UUID pay = paymentOf(r);
    assertThat(get("/v1/subscriptions/me", a).json().get("status").asString()).isEqualTo("incomplete");
    assertThat(get("/v1/entitlements/me", a).json().get("tier").asString()).isEqualTo("FREE");

    Res ack = deliver(delivery("payment.succeeded", pay));
    assertThat(ack.status()).as(ack.toString()).isEqualTo(200);
    assertThat(ack.json().get("outcome").asString()).isEqualTo("applied");
    assertThat(get("/v1/subscriptions/me", a).json().get("status").asString()).isEqualTo("active");
    assertThat(get("/v1/entitlements/me", a).json().get("tier").asString()).isEqualTo("VIP");
    // outside the supplier's state: IGST, same total
    assertThat(one("SELECT tax_regime || ':' || total_paise FROM billing.invoices WHERE account_id = ?", a))
        .isEqualTo("inter:58882");
    // the key replays what was answered at the time (202), not the later state
    Res replay = checkout(a, key, "VIP", "pm_mock_delayed", "MH");
    assertThat(replay.status()).isEqualTo(202);
    assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.body()).isEqualTo(r.body());
  }

  @Test
  void aProviderOutageKeepsTheKeyAndARetryAfterTheLeaseChargesOnce() {
    UUID a = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    int before = provider.chargesCreated();
    provider.unavailable(true);
    Res down;
    try {
      down = checkout(a, key, "PREMIUM", "pm_mock_success", "KA");
    } finally {
      provider.unavailable(false);
    }
    assertThat(down.status()).as(down.toString()).isEqualTo(503);
    assertThat(down.code()).isEqualTo("PROVIDER_UNAVAILABLE");
    assertThat(down.header("Retry-After")).isNotNull();
    // inside the lease a retry is told to wait, never charged in parallel
    Res busy = checkout(a, key, "PREMIUM", "pm_mock_success", "KA");
    assertThat(busy.status()).isEqualTo(409);
    assertThat(busy.code()).isEqualTo("IDEMPOTENCY_IN_PROGRESS");
    expireLease(a, key);
    Res retry = checkout(a, key, "PREMIUM", "pm_mock_success", "KA");
    assertThat(retry.status()).as(retry.toString()).isEqualTo(201);
    assertThat(retry.header("Idempotent-Replayed")).isNull();
    assertThat(provider.chargesCreated() - before).isEqualTo(1);
    assertThat(count("SELECT count(1) FROM billing.payments WHERE account_id = ?", a)).isEqualTo(1);
  }

  @Test
  void whatCheckoutRefuses() {
    UUID a = UUID.randomUUID();
    Res noKey = send("POST", "/v1/subscriptions/checkout", checkoutBody("PREMIUM", "pm_mock_success", "KA"),
        "application/json", auth(a));
    assertThat(noKey.status()).isEqualTo(400);
    assertThat(noKey.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    Res card = checkout(a, UUID.randomUUID(), "PREMIUM", "4111111111111111", "KA");
    assertThat(card.status()).as("a card number is not a payment method").isEqualTo(400);
    assertThat(card.body()).contains("paymentMethod");
    Res state = checkout(a, UUID.randomUUID(), "PREMIUM", "pm_mock_success", "ZZ");
    assertThat(state.status()).isEqualTo(400);
    assertThat(state.body()).contains("billingState");
    Res free = checkout(a, UUID.randomUUID(), "FREE", "pm_mock_success", "KA");
    assertThat(free.status()).isEqualTo(422);
    assertThat(free.code()).isEqualTo("PLAN_NOT_PURCHASABLE");
    Res gold = checkout(a, UUID.randomUUID(), "GOLD", "pm_mock_success", "KA");
    assertThat(gold.status()).isEqualTo(404);
    assertThat(gold.code()).isEqualTo("PLAN_NOT_FOUND");
    Res unknownField = send("POST", "/v1/subscriptions/checkout",
        "{\"plan\":\"PREMIUM\",\"paymentMethod\":\"pm_mock_success\",\"billingState\":\"KA\",\"cardNumber\":\"x\"}",
        "application/json", auth(a, "Idempotency-Key", UUID.randomUUID().toString()));
    assertThat(unknownField.status()).isEqualTo(400);
    assertThat(unknownField.code()).isEqualTo("UNKNOWN_FIELD");
    assertThat(count("SELECT count(1) FROM billing.subscriptions WHERE account_id = ?", a)).isZero();
  }

  @Test
  void checkoutAndCancelNeedARecentSignInAndAUserToken() {
    UUID a = UUID.randomUUID();
    String stale = IDP.user(a).audience("billing").authTime(Instant.now().minus(Duration.ofMinutes(20))).sign();
    Res r = send("POST", "/v1/subscriptions/checkout", checkoutBody("PREMIUM", "pm_mock_success", "KA"),
        "application/json", authWith(stale, "Idempotency-Key", UUID.randomUUID().toString()));
    assertThat(r.status()).isEqualTo(401);
    assertThat(r.code()).isEqualTo("STEP_UP_REQUIRED");
    Res c = send("POST", "/v1/subscriptions/me:cancel", null, null, authWith(stale));
    assertThat(c.code()).isEqualTo("STEP_UP_REQUIRED");
    String admin = IDP.admin(a, "SUPPORT").audience("billing").scope("admin").sign();
    Res asAdmin = send("POST", "/v1/subscriptions/checkout", checkoutBody("PREMIUM", "pm_mock_success", "KA"),
        "application/json", authWith(admin, "Idempotency-Key", UUID.randomUUID().toString()));
    assertThat(asAdmin.status()).isEqualTo(403);
    assertThat(asAdmin.code()).isEqualTo("USER_TOKEN_REQUIRED");
    Res anon = send("GET", "/v1/subscriptions/me", null, null);
    assertThat(anon.status()).isEqualTo(401);
  }

  @Test
  void cancelIsAtPeriodEndKeepsThePlanAndIsIdempotent() {
    UUID a = UUID.randomUUID();
    Res nothing = cancel(a);
    assertThat(nothing.status()).isEqualTo(404);
    assertThat(nothing.code()).isEqualTo("SUBSCRIPTION_NOT_FOUND");
    subscribe(a);
    Res c1 = cancel(a);
    assertThat(c1.status()).as(c1.toString()).isEqualTo(200);
    assertThat(c1.json().get("cancelAtPeriodEnd").asBoolean()).isTrue();
    assertThat(c1.json().get("status").asString()).isEqualTo("active");
    Res c2 = cancel(a);
    assertThat(c2.status()).isEqualTo(200);
    assertThat(c2.json().get("version").asInt()).isEqualTo(c1.json().get("version").asInt());
    List<JsonNode> changed = outbox(a, "billing.subscription.changed");
    assertThat(changed).hasSize(2);
    assertThat(changed.get(1).get("cancelAtPeriodEnd").asBoolean()).isTrue();
    assertThat(outboxRows(a, "billing.entitlements.changed")).as("still PREMIUM until the period ends").isEqualTo(1);
    assertThat(get("/v1/entitlements/me", a).json().get("tier").asString()).isEqualTo("PREMIUM");
  }
}
