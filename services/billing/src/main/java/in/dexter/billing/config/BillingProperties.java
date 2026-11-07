package in.dexter.billing.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code dexter.billing.*}. */
@ConfigurationProperties("dexter.billing")
public record BillingProperties(
    @DefaultValue("mock") String provider,
    /* the supplier's own state for the mock GST split (CGST + SGST inside it, IGST outside) */
    @DefaultValue("KA") String supplierState,
    @DefaultValue Checkout checkout,
    @DefaultValue Jobs jobs,
    @DefaultValue MockProvider mockProvider,
    @DefaultValue Relay relay) {

  /** Mode B: how long a checkout attempt holds its key before a retry or the sweeper may take it over. */
  public record Checkout(@DefaultValue("30s") Duration lease) {}

  /** The checkout sweeper and the period-end job (every instance; SKIP LOCKED shares the work). */
  public record Jobs(@DefaultValue("false") boolean enabled, @DefaultValue("15s") Duration sweepEvery,
      @DefaultValue("60s") Duration periodEndEvery, @DefaultValue("100") int batch,
      @DefaultValue("3d") Duration pastDueGrace) {}

  /**
   * The mock payment provider: HMAC secret for its webhooks, where they are delivered (blank = this service's own
   * port), how long after a charge they arrive, how long a "delayed" outcome takes to be decided, and whether they
   * are posted ({@code http}) or kept for a test to send ({@code memory}).
   */
  public record MockProvider(String webhookSecret, String webhookBaseUrl,
      @DefaultValue("300ms") Duration webhookDelay, @DefaultValue("3s") Duration delayedOutcomeAfter,
      @DefaultValue("true") boolean webhooks, @DefaultValue("4") int deliveryAttempts,
      @DefaultValue("http") String delivery) {}

  /** Outbox relay (MASTER_PLAN 6.3) as billing_relay, plus topic creation and the idempotency-key purge. */
  public record Relay(@DefaultValue("false") boolean enabled, String url, String username, String password,
      String bootstrapServers, @DefaultValue("3") int partitions, @DefaultValue("1") short replication) {}
}
