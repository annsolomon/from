package in.dexter.commerce.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code dexter.commerce.*}. */
@ConfigurationProperties("dexter.commerce")
public record CommerceProperties(
    @DefaultValue Clicks clicks,
    @DefaultValue Feed feed,
    @DefaultValue Admin admin,
    @DefaultValue Jobs jobs,
    /* affiliate network code -> its postback secret (HMAC-SHA256, >= 16 bytes; blank = network disabled) */
    @DefaultValue Map<String, String> affiliateSecrets,
    @DefaultValue Consumers consumers,
    @DefaultValue Relay relay) {

  /**
   * Signed redirects: the HMAC key (base64, >= 32 bytes) of {@code /r/{token}}, how long a minted link works, and
   * the salt of the ip / user-agent / visitor hashes.
   */
  public record Clicks(String tokenKey, @DefaultValue("7d") Duration linkTtl, String hashSalt) {}

  /** The ranked feed works over at most this many active deals. */
  public record Feed(@DefaultValue("500") int maxActive) {}

  /** The commerce_admin pool (admin endpoints; RLS lets only operator roles in). Blank URL = the app pool's URL. */
  public record Admin(String dbUrl, @DefaultValue("commerce_admin") String dbUsername, String dbPassword,
      @DefaultValue("5") int dbPoolSize) {}

  /** The counter flusher (every instance; each drain is atomic, so instances share the work). */
  public record Jobs(@DefaultValue("false") boolean enabled, @DefaultValue("10s") Duration flushEvery) {}

  /** The three projection consumers (MASTER_PLAN 6.2). */
  public record Consumers(@DefaultValue("false") boolean enabled, String bootstrapServers) {}

  /**
   * Outbox relay (MASTER_PLAN 6.3) as commerce_relay: topics, the consumers' retry stages, the daily partitions of
   * deal_clicks / ad_impressions_raw (created ahead, dropped after {@code rawRetention}), the inbox and
   * Idempotency-Key purge.
   */
  public record Relay(@DefaultValue("false") boolean enabled, String url, String username, String password,
      String bootstrapServers, @DefaultValue("3") int partitions, @DefaultValue("1") short replication,
      @DefaultValue("90d") Duration rawRetention) {}
}
