package in.dexter.billing.config;

import in.dexter.billing.app.BillingStore;
import in.dexter.billing.app.CheckoutService;
import in.dexter.billing.app.EntitlementService;
import in.dexter.billing.app.InvoiceService;
import in.dexter.billing.app.InvoiceStore;
import in.dexter.billing.app.PaymentOutcomes;
import in.dexter.billing.app.PaymentProvider;
import in.dexter.billing.app.PlanService;
import in.dexter.billing.app.SubscriptionService;
import in.dexter.billing.app.WebhookService;
import in.dexter.billing.domain.IndianStates;
import in.dexter.billing.infra.BillingRepository;
import in.dexter.billing.infra.InvoiceRepository;
import in.dexter.billing.infra.provider.HttpWebhookSink;
import in.dexter.billing.infra.provider.MemoryWebhookSink;
import in.dexter.billing.infra.provider.MockPaymentProvider;
import in.dexter.cache.CacheInvalidator;
import in.dexter.cache.TenantCache;
import in.dexter.core.time.Clocks;
import in.dexter.db.page.KeysetPager;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.IdempotencyEngine;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires billing (MASTER_PLAN 2.5: config wires, app/infra stay framework-light). TenantTx, KeysetPager,
 * IdempotencyEngine, OutboxWriter, the JWT interceptor and (with Redis) dexter-cache come from the platform
 * libraries' auto-configuration.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfiguration {

  @Bean
  Clock billingClock() {
    return Clocks.systemUtc();
  }

  @Bean
  BillingStore billingStore(JdbcClient jdbc) {
    return new BillingRepository(jdbc);
  }

  @Bean
  InvoiceStore invoiceStore(JdbcClient jdbc, KeysetPager pager) {
    return new InvoiceRepository(jdbc, pager);
  }

  /** Where the mock provider's webhooks go: posted to this service (http) or kept for a test to send (memory). */
  @Bean
  MockPaymentProvider.Sink mockWebhookSink(BillingProperties props, Environment env) {
    if ("memory".equals(props.mockProvider().delivery())) {
      return new MemoryWebhookSink();
    }
    String base = props.mockProvider().webhookBaseUrl();
    return new HttpWebhookSink(() -> base != null && !base.isBlank() ? base.replaceAll("/+$", "")
        : "http://127.0.0.1:" + env.getProperty("local.server.port", env.getProperty("server.port", "8087")),
        props.mockProvider().deliveryAttempts());
  }

  @Bean(destroyMethod = "close")
  MockPaymentProvider mockPaymentProvider(BillingProperties props, MockPaymentProvider.Sink sink, JsonMapper mapper,
      Clock clock) {
    BillingProperties.MockProvider m = props.mockProvider();
    String secret = m.webhookSecret();
    if (secret == null || secret.length() < 16) {
      throw new IllegalStateException("dexter.billing.mock-provider.webhook-secret must be at least 16 characters");
    }
    return new MockPaymentProvider(new MockPaymentProvider.Settings(secret.getBytes(StandardCharsets.UTF_8),
        m.webhookDelay(), m.delayedOutcomeAfter(), m.webhooks()), sink, mapper, clock);
  }

  @Bean
  EntitlementService entitlementService(TenantTx tx, BillingStore store, OutboxWriter outbox, JsonMapper mapper,
      Clock clock, ObjectProvider<TenantCache> cache, ObjectProvider<CacheInvalidator> invalidator) {
    return new EntitlementService(tx, store, outbox, mapper, clock, cache.getIfAvailable(),
        invalidator.getIfAvailable());
  }

  @Bean
  PaymentOutcomes paymentOutcomes(BillingStore store, InvoiceStore invoices, OutboxWriter outbox,
      EntitlementService entitlements, BillingProperties props) {
    if (!IndianStates.valid(props.supplierState())) {
      throw new IllegalStateException("dexter.billing.supplier-state is not a state code: " + props.supplierState());
    }
    return new PaymentOutcomes(store, invoices, outbox, entitlements, props.supplierState());
  }

  @Bean
  CheckoutService checkoutService(TenantTx tx, IdempotencyEngine idempotency, BillingStore store,
      InvoiceStore invoices, PaymentOutcomes outcomes, List<PaymentProvider> providers, JsonMapper mapper,
      Clock clock, BillingProperties props) {
    PaymentProvider provider = providers.stream().filter(p -> p.name().equals(props.provider())).findFirst()
        .orElseThrow(() -> new IllegalStateException("no payment provider " + props.provider()));
    return new CheckoutService(tx, idempotency, store,
        paymentId -> invoices.byPayment(paymentId).map(InvoiceStore.InvoiceRow::id), outcomes, provider, mapper,
        clock, props.checkout().lease());
  }

  @Bean
  SubscriptionService subscriptionService(TenantTx tx, IdempotencyEngine idempotency, BillingStore store,
      PaymentOutcomes outcomes, JsonMapper mapper, Clock clock, BillingProperties props) {
    return new SubscriptionService(tx, idempotency, store, outcomes, mapper, clock, props.jobs().pastDueGrace());
  }

  @Bean
  PlanService planService(TenantTx tx, BillingStore store, JsonMapper mapper) {
    return new PlanService(tx, store, mapper);
  }

  @Bean
  InvoiceService invoiceService(TenantTx tx, InvoiceStore invoices) {
    return new InvoiceService(tx, invoices);
  }

  @Bean
  WebhookService webhookService(TenantTx tx, BillingStore store, PaymentOutcomes outcomes,
      List<PaymentProvider> providers, Clock clock) {
    return new WebhookService(tx, store, outcomes, providers, clock);
  }

  @Bean
  BillingJobs billingJobs(CheckoutService checkout, SubscriptionService subscriptions, BillingProperties props) {
    return new BillingJobs(checkout, subscriptions, props.jobs());
  }
}
