package in.dexter.billing;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.billing.infra.provider.MemoryWebhookSink;
import in.dexter.billing.support.BillingIT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * POST /v1/webhooks/{provider} (MASTER_PLAN P16). The two named tests: <b>a replayed webhook has a single effect</b>,
 * and <b>out-of-order webhooks (succeeded before created)</b> end in the right state. Then the signature rules.
 */
class WebhookIT extends BillingIT {

  /** A pending (delayed) checkout: nothing is decided until a webhook says so. */
  UUID pendingCheckout(UUID account) {
    Res r = checkout(account, UUID.randomUUID(), "PREMIUM", "pm_mock_delayed", "KA");
    assertThat(r.status()).as(r.toString()).isEqualTo(202);
    return paymentOf(r);
  }

  @Test
  void aReplayedWebhookHasASingleEffect() throws Exception {
    UUID a = UUID.randomUUID();
    UUID pay = pendingCheckout(a);
    MemoryWebhookSink.Delivery succeeded = delivery("payment.succeeded", pay);

    Res first = deliver(succeeded);
    assertThat(first.status()).as(first.toString()).isEqualTo(200);
    assertThat(first.json().get("status").asString()).isEqualTo("processed");
    assertThat(first.json().get("outcome").asString()).isEqualTo("applied");
    // the same delivery again, sequentially and then ten at once
    for (int i = 0; i < 3; i++) {
      Res again = deliver(succeeded);
      assertThat(again.status()).isEqualTo(200);
      assertThat(again.json().get("status").asString()).isEqualTo("duplicate");
    }
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Res>> futures = new ArrayList<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 10; i++) {
        futures.add(pool.submit(() -> {
          go.await();
          return deliver(succeeded);
        }));
      }
      go.countDown();
      for (Future<Res> f : futures) {
        Res r = f.get();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("status").asString()).isEqualTo("duplicate");
      }
    }
    // one effect: one recorded event, one activation, one invoice, one announcement of each kind
    assertThat(count("SELECT count(1) FROM billing.webhook_events WHERE account_id = ?", a)).isEqualTo(1);
    assertThat(count("SELECT count(1) FROM billing.invoices WHERE account_id = ?", a)).isEqualTo(1);
    assertThat(outboxRows(a, "billing.subscription.changed")).isEqualTo(1);
    assertThat(outboxRows(a, "billing.entitlements.changed")).isEqualTo(1);
    // v1: the checkout's own answer taught it the provider's id; v2: the webhook decided it; then nothing more
    assertThat(one("SELECT status || ':' || version FROM billing.payments WHERE id = ?", pay))
        .as("decided once (pending -> succeeded), never touched again").isEqualTo("succeeded:2");
    // a second, distinct event saying the same thing is processed and changes nothing
    Res created = deliver(delivery("payment.created", pay));
    assertThat(created.json().get("status").asString()).isEqualTo("processed");
    assertThat(created.json().get("outcome").asString()).isEqualTo("no_change");
    assertThat(outboxRows(a, "billing.subscription.changed")).isEqualTo(1);
  }

  @Test
  void outOfOrderWebhooksSucceededBeforeCreated() {
    UUID a = UUID.randomUUID();
    UUID pay = pendingCheckout(a);
    MemoryWebhookSink.Delivery created = delivery("payment.created", pay);
    MemoryWebhookSink.Delivery succeeded = delivery("payment.succeeded", pay);

    Res s = deliver(succeeded);
    assertThat(s.json().get("outcome").asString()).isEqualTo("applied");
    Res c = deliver(created);
    assertThat(c.status()).isEqualTo(200);
    assertThat(c.json().get("status").asString()).isEqualTo("processed");
    assertThat(c.json().get("outcome").asString()).as("a late 'created' cannot regress 'succeeded'")
        .isEqualTo("no_change");
    assertThat(one("SELECT status FROM billing.payments WHERE id = ?", pay)).isEqualTo("succeeded");
    assertThat(get("/v1/subscriptions/me", a).json().get("status").asString()).isEqualTo("active");
    assertThat(outboxRows(a, "billing.subscription.changed")).isEqualTo(1);
  }

  @Test
  void outOfOrderFailureBeforeCreatedAlsoHolds() {
    UUID a = UUID.randomUUID();
    Res r = checkout(a, UUID.randomUUID(), "PREMIUM", "pm_mock_delayed_decline", "KA");
    assertThat(r.status()).isEqualTo(202);
    UUID pay = paymentOf(r);
    MemoryWebhookSink.Delivery failed = delivery("payment.failed", pay);
    MemoryWebhookSink.Delivery created = delivery("payment.created", pay);
    assertThat(deliver(failed).json().get("outcome").asString()).isEqualTo("applied");
    assertThat(deliver(created).json().get("outcome").asString()).isEqualTo("no_change");
    assertThat(one("SELECT status || ':' || failure_code FROM billing.payments WHERE id = ?", pay))
        .isEqualTo("failed:card_declined");
    assertThat(get("/v1/subscriptions/me", a).json().get("status").asString()).isEqualTo("incomplete_expired");
    // a "succeeded" for a payment already failed is refused by the same rule
    assertThat(outboxRows(a, "billing.subscription.changed")).isZero();
  }

  @Test
  void theSignatureIsTheCredential() {
    UUID a = UUID.randomUUID();
    UUID pay = pendingCheckout(a);
    MemoryWebhookSink.Delivery d = delivery("payment.succeeded", pay);
    byte[] tampered = d.text().replace("\"succeeded\"", "\"failed\"").getBytes(StandardCharsets.UTF_8);
    Res t = sendBytes("POST", "/v1/webhooks/mock", tampered, "application/json", "Webhook-Signature", d.signature());
    assertThat(t.status()).isEqualTo(400);
    assertThat(t.code()).isEqualTo("WEBHOOK_SIGNATURE_INVALID");
    Res none = sendBytes("POST", "/v1/webhooks/mock", d.body(), "application/json");
    assertThat(none.code()).isEqualTo("WEBHOOK_SIGNATURE_INVALID");
    Res old = sendBytes("POST", "/v1/webhooks/mock", d.body(), "application/json", "Webhook-Signature",
        sign(d.body(), Instant.now().minus(Duration.ofMinutes(6))));
    assertThat(old.code()).as("outside the ±5 minute window").isEqualTo("WEBHOOK_SIGNATURE_INVALID");
    assertThat(old.json().get("reason").asString()).isEqualTo("STALE");
    Res unknown = sendBytes("POST", "/v1/webhooks/razorpay", d.body(), "application/json", "Webhook-Signature",
        d.signature());
    assertThat(unknown.status()).isEqualTo(404);
    assertThat(unknown.code()).isEqualTo("PROVIDER_UNKNOWN");
    assertThat(one("SELECT status FROM billing.payments WHERE id = ?", pay)).as("nothing above was applied")
        .isEqualTo("pending");
    assertThat(count("SELECT count(1) FROM billing.webhook_events WHERE account_id = ?", a)).isZero();
  }

  @Test
  void signedEventsThatNameNoPaymentAreAcknowledgedAndIgnored() {
    byte[] stranger = ("{\"id\":\"evt_" + UUID.randomUUID().toString().replace("-", "") + "\",\"type\":"
        + "\"payment.succeeded\",\"data\":{\"reference\":\"" + UUID.randomUUID() + "\",\"status\":\"succeeded\"}}")
        .getBytes(StandardCharsets.UTF_8);
    Res r = sendBytes("POST", "/v1/webhooks/mock", stranger, "application/json", "Webhook-Signature",
        sign(stranger, Instant.now()));
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.json().get("status").asString()).isEqualTo("ignored");
    byte[] junk = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
    Res bad = sendBytes("POST", "/v1/webhooks/mock", junk, "application/json", "Webhook-Signature",
        sign(junk, Instant.now()));
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.code()).isEqualTo("WEBHOOK_INVALID");
  }
}
