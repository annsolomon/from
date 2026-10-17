package in.dexter.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code dexter.ledger.*}. */
@ConfigurationProperties("dexter.ledger")
public record LedgerProperties(@DefaultValue Relay relay, @DefaultValue Imports imports) {

  /**
   * CSV imports (P08): the S3-compatible store (ADR-0003) and the worker. {@code publicEndpoint} is the object
   * store as the browser reaches it (presigned PUT); {@code endpoint} as the service reaches it.
   */
  public record Imports(@DefaultValue("http://127.0.0.1:8333") String endpoint, String publicEndpoint,
      @DefaultValue("dexter-imports") String bucket, @DefaultValue("us-east-1") String region,
      @DefaultValue("dexter-dev") String accessKey, String secretKey,
      @DefaultValue("10485760") long maxBytes, @DefaultValue("50000") int maxRows,
      @DefaultValue("true") boolean workerEnabled, @DefaultValue("1s") java.time.Duration pollInterval,
      @DefaultValue("10m") java.time.Duration stale, @DefaultValue("3") int maxAttempts) {}


  /** Outbox relay (MASTER_PLAN 6.3) as ledger_relay, plus topic creation and the idempotency-key purge. */
  public record Relay(@DefaultValue("false") boolean enabled, String url, String username, String password,
      String bootstrapServers, @DefaultValue("3") int partitions, @DefaultValue("1") short replication) {}
}
