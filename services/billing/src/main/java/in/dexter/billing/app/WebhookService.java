package in.dexter.billing.app;

import in.dexter.billing.app.BillingStore.WebhookRow;
import in.dexter.billing.app.BillingViews.WebhookAck;
import in.dexter.billing.app.PaymentProvider.WebhookEvent;
import in.dexter.billing.domain.BillingErrors;
import in.dexter.billing.domain.WebhookSignature;
import in.dexter.core.error.DexterException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code POST /v1/webhooks/{provider}} (MASTER_PLAN P16, 7.3): no token - the provider's HMAC signature over the raw
 * body, inside a ±5 minute window, is the credential. Then:
 *
 * <ol>
 *   <li>the payment the event names says which account it belongs to (an owner function, the A-30 pre-auth
 *       shape);</li>
 *   <li>in that account's transaction the event id is recorded - a replay conflicts on the primary key and is
 *       acknowledged as {@code duplicate} with no effect;</li>
 *   <li>the outcome goes through {@link PaymentOutcomes}, the same path as the checkout's own answer, so the order
 *       events arrive in cannot matter: "succeeded" before "created" decides the payment once and "created"
 *       afterwards changes nothing.</li>
 * </ol>
 *
 * Every accepted delivery is answered 200 (a provider retries anything else), including events that name no Dexter
 * payment, which are {@code ignored}.
 */
public final class WebhookService {
  private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
  public static final String PATH = "/v1/webhooks";
  static final Tenant LOOKUP = new Tenant(new UUID(0, 0xB111_0001L), "WEBHOOK", null);

  private final TenantTx tx;
  private final BillingStore store;
  private final PaymentOutcomes outcomes;
  private final Map<String, PaymentProvider> providers;
  private final Clock clock;

  public WebhookService(TenantTx tx, BillingStore store, PaymentOutcomes outcomes, List<PaymentProvider> providers,
      Clock clock) {
    this.tx = tx;
    this.store = store;
    this.outcomes = outcomes;
    this.providers = providers.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
        PaymentProvider::name, p -> p));
    this.clock = clock;
  }

  public WebhookAck receive(String providerName, String signature, byte[] body) {
    PaymentProvider provider = providers.get(providerName);
    if (provider == null) {
      throw new DexterException(BillingErrors.PROVIDER_UNKNOWN, "No payment provider " + providerName);
    }
    WebhookSignature.Check check = provider.verify(signature, body == null ? new byte[0] : body, clock.instant());
    if (check != WebhookSignature.Check.OK) {
      log.warn("WEBHOOK_REJECTED provider={} reason={}", providerName, check);
      throw new DexterException(BillingErrors.WEBHOOK_SIGNATURE_INVALID, "Signature " + check.name().toLowerCase(
          java.util.Locale.ROOT), List.of(), Map.of("reason", check.name()), null);
    }
    WebhookEvent ev;
    try {
      ev = provider.parse(body);
    } catch (IllegalArgumentException e) {
      throw new DexterException(BillingErrors.WEBHOOK_INVALID, e.getMessage());
    }
    if (ev.reference() == null) {
      return new WebhookAck("ignored", ev.eventId(), null);
    }
    Optional<UUID> account = tx.read(LOOKUP, () -> store.paymentAccount(ev.reference()));
    if (account.isEmpty()) {
      log.info("WEBHOOK_IGNORED provider={} event={} reason=unknown_payment", providerName, ev.eventId());
      return new WebhookAck("ignored", ev.eventId(), null);
    }
    Tenant tenant = new Tenant(account.get(), "WEBHOOK", null);
    String payload = new String(body, StandardCharsets.UTF_8);
    return tx.write(tenant, () -> {
      Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
      if (!store.recordWebhook(new WebhookRow(provider.name(), ev.eventId(), tenant.accountId(), ev.type(),
          ev.reference(), payload, now))) {
        return new WebhookAck("duplicate", ev.eventId(), null);
      }
      PaymentOutcomes.Result r = outcomes.apply(tenant, ev.reference(), ev.status(), ev.providerPaymentId(),
          ev.providerSubscriptionId(), ev.failureCode(), now);
      String outcome = r == PaymentOutcomes.Result.APPLIED ? "applied" : "no_change";
      store.markWebhookProcessed(provider.name(), ev.eventId(), outcome, now);
      return new WebhookAck("processed", ev.eventId(), outcome);
    });
  }
}
