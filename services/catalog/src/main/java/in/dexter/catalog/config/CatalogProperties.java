package in.dexter.catalog.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code dexter.catalog.*}. */
@ConfigurationProperties("dexter.catalog")
public record CatalogProperties(@DefaultValue Admin admin, @DefaultValue Relay relay,
    @DefaultValue Ingestion ingestion) {

  /** The catalog_admin pool (admin endpoints; RLS lets only editor roles in). Blank URL = the app pool's URL. */
  public record Admin(String dbUrl, @DefaultValue("catalog_admin") String dbUsername, String dbPassword,
      @DefaultValue("5") int dbPoolSize) {}

  /** Outbox relay (MASTER_PLAN 6.3) as catalog_relay, plus topic creation. */
  public record Relay(@DefaultValue("false") boolean enabled, String url, String username, String password,
      String bootstrapServers, @DefaultValue("3") int partitions, @DefaultValue("1") short replication) {}

  /**
   * P10 ingestion: the worker (runs + scheduler), the SSRF-safe fetcher (allowlist, dev hosts, caps), politeness,
   * resume and the snapshot bucket.
   *
   * @param allowedHosts issuer hosts (a host also allows its subdomains) or {@code host:port}
   * @param devHosts     exact {@code host:port} allowed over http and on private addresses (a local mock issuer
   *                     site; never in production)
   */
  public record Ingestion(@DefaultValue("true") boolean workerEnabled, @DefaultValue("2s") Duration pollInterval,
      @DefaultValue("60s") Duration scheduleInterval, List<String> allowedHosts, List<String> devHosts,
      @DefaultValue("5242880") int maxBytes, @DefaultValue("10s") Duration timeout,
      @DefaultValue("3") int maxRedirects, @DefaultValue("2s") Duration politeness,
      @DefaultValue("10m") Duration staleAfter, @DefaultValue("3") int maxAttempts,
      @DefaultValue("50") int sitemapMaxUrls, @DefaultValue Snapshots snapshots) {}

  /** The snapshot bucket on the S3-compatible store (ADR-0003). */
  public record Snapshots(@DefaultValue("http://127.0.0.1:8333") String endpoint,
      @DefaultValue("dexter-catalog-snapshots") String bucket, @DefaultValue("us-east-1") String region,
      @DefaultValue("dexter-dev") String accessKey, String secretKey) {}
}
