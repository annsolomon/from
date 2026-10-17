package in.dexter.identity.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code dexter.identity.*}. Secrets (KEK, pepper, client secrets, DB passwords) come from the environment
 * ({@code .env} in dev, Vault in P19) and are never committed outside {@code .env.example}.
 */
@ConfigurationProperties("dexter.identity")
public record IdentityProperties(
    String issuer,
    @DefaultValue({"identity", "ledger", "catalog", "portfolio", "planning", "reco", "billing", "commerce", "audit"})
    List<String> audiences,
    @DefaultValue("10m") Duration accessTokenTtl,
    @DefaultValue("30d") Duration refreshIdle,
    @DefaultValue("90d") Duration refreshAbsolute,
    @DefaultValue({"dexter-app", "dexter-web", "dexter-trail"}) List<String> publicClients,
    @DefaultValue("dexter-app") String defaultClient,
    @DefaultValue("24h") Duration emailVerifyTtl,
    @DefaultValue("30m") Duration passwordResetTtl,
    @DefaultValue("5") int tokensPerHour,
    @DefaultValue Links links,
    @DefaultValue Keys keys,
    @DefaultValue Kek kek,
    String phonePepper,
    @DefaultValue("5m") Duration serviceTokenTtl,
    Map<String, ServiceClient> serviceClients,
    @DefaultValue Mail mail,
    @DefaultValue Hashing hashing,
    @DefaultValue Relay relay) {

  public IdentityProperties {
    serviceClients = serviceClients == null ? Map.of() : Map.copyOf(serviceClients);
  }

  /** Email link templates; {@code {token}} is replaced by the raw token. */
  public record Links(
      @DefaultValue("http://localhost:8099/p04-identity.html#verify={token}") String verifyEmail,
      @DefaultValue("http://localhost:8099/p04-identity.html#reset={token}") String resetPassword,
      @DefaultValue("http://localhost:8099/p04-identity.html") String signIn) {}

  /** Signing-key lifecycle (MASTER_PLAN 7.1). */
  public record Keys(
      @DefaultValue("30d") Duration rotateEvery,
      @DefaultValue("2h") Duration retainRetired,
      @DefaultValue("10m") Duration checkEvery) {}

  /** Key-encryption keys: {@code keys.<id>} = base64 of 32 random bytes; new material uses {@code activeId}. */
  public record Kek(@DefaultValue("k1") String activeId, Map<String, String> keys) {
    public Kek {
      keys = keys == null ? Map.of() : Map.copyOf(keys);
    }
  }

  /** A calling service: its secret (env) and the audiences it may request. Empty secret = client disabled. */
  public record ServiceClient(String secret, List<String> audiences) {}

  /** {@code mode}: smtp (Mailpit in dev) or memory (tests). */
  public record Mail(@DefaultValue("smtp") String mode, @DefaultValue("Dexter <no-reply@dexter.local>") String from,
      @DefaultValue("64") int maxInFlight) {}

  /** Argon2 bulkhead (each hash ~19 MiB). */
  public record Hashing(@DefaultValue("8") int maxConcurrent, @DefaultValue("5s") Duration maxWait) {}

  /** Outbox relay (MASTER_PLAN 6.3) as identity_relay, plus topic creation and the idempotency-key purge. */
  public record Relay(@DefaultValue("false") boolean enabled, String url, String username, String password,
      String bootstrapServers, @DefaultValue("3") int partitions, @DefaultValue("1") short replication) {}
}
