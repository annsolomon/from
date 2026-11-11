package in.dexter.audit.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code dexter.audit.*}. */
@ConfigurationProperties("dexter.audit")
public record AuditProperties(
    @DefaultValue Admin admin,
    @DefaultValue Store store,
    @DefaultValue Notify notify,
    @DefaultValue Privacy privacy,
    @DefaultValue Jobs jobs,
    @DefaultValue Consumers consumers,
    @DefaultValue Relay relay) {

  /** The audit_admin pool (operators read the log, the dashboard, a request). Blank URL = the app pool's URL. */
  public record Admin(String dbUrl, @DefaultValue("audit_admin") String dbUsername, String dbPassword,
      @DefaultValue("4") int dbPoolSize) {}

  /** The audit bucket: export parts and archives, chain anchors. */
  public record Store(String endpoint, String publicEndpoint, @DefaultValue("dexter-audit") String bucket,
      String accessKey, String secretKey, @DefaultValue("us-east-1") String region) {}

  /**
   * Email / push delivery: exponential backoff from {@code backoffBase} to {@code backoffCap}, {@code maxAttempts}
   * in all; {@code mockFailFirst} makes the mock transport refuse each notification's first n attempts (dev: 1).
   */
  public record Notify(@DefaultValue("30s") Duration backoffBase, @DefaultValue("1h") Duration backoffCap,
      @DefaultValue("6") int maxAttempts, @DefaultValue("0") int mockFailFirst) {}

  /**
   * The saga: the participating services, how long a phase waits before it re-asks, how many asks before the
   * request fails, and the key of the deletion pseudonym (base64, >= 32 bytes).
   */
  public record Privacy(
      @DefaultValue({"identity", "ledger", "portfolio", "planning", "reco", "billing", "commerce"}) List<String> services,
      @DefaultValue("2m") Duration phaseTimeout, @DefaultValue("5") int maxAttempts, String subjectKey) {}

  /** The notification worker, the saga's job and the anchor job (every instance; each claims with SKIP LOCKED). */
  public record Jobs(@DefaultValue("false") boolean enabled, @DefaultValue("1s") Duration notifyEvery,
      @DefaultValue("2s") Duration sagaEvery, @DefaultValue("24h") Duration anchorEvery) {}

  /** The three consumers: events (audit + notifications), activity, privacy acknowledgements. */
  public record Consumers(@DefaultValue("false") boolean enabled, String bootstrapServers) {}

  /**
   * Outbox relay as audit_relay: topics (audit's own, the consumers' stages and the sources as their producers
   * declare them), audit_log's monthly partitions (kept) and activity_events' daily ones (dropped after
   * {@code activityRetention}), the inbox and Idempotency-Key purge.
   */
  public record Relay(@DefaultValue("false") boolean enabled, String url, String username, String password,
      String bootstrapServers, @DefaultValue("3") int partitions, @DefaultValue("1") short replication,
      @DefaultValue("90d") Duration activityRetention) {}
}
