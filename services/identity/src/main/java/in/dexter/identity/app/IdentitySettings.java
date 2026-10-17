package in.dexter.identity.app;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Identity's runtime settings, built by config from {@code dexter.identity.*} (the use-cases never read
 * configuration directly).
 *
 * @param issuer             {@code iss} of every token (services verify it exactly)
 * @param audiences          {@code aud} of user access tokens (the services that accept them)
 * @param publicClients      client ids allowed at login/registration (the apps; no secret)
 * @param verifyLink         email link, {@code {token}} replaced by the raw token
 * @param resetLink          email link, {@code {token}} replaced by the raw token
 * @param tokensPerHour      verification/reset emails per account and purpose per hour
 * @param keyRetainRetired   how long a retired signing key stays published (≥ max token lifetime + JWKS cache)
 */
public record IdentitySettings(
    String issuer,
    List<String> audiences,
    Duration accessTokenTtl,
    Duration refreshIdle,
    Duration refreshAbsolute,
    Set<String> publicClients,
    String defaultClient,
    Duration emailVerifyTtl,
    Duration passwordResetTtl,
    int tokensPerHour,
    String verifyLink,
    String resetLink,
    String signInLink,
    Duration keyRotateEvery,
    Duration keyRetainRetired,
    Duration serviceTokenTtl,
    Map<String, ServiceClient> serviceClients) {

  /** A confidential client-credentials client (another Dexter service). Only the secret's SHA-256 is kept. */
  public record ServiceClient(String id, byte[] secretSha256, Set<String> audiences) {
    public ServiceClient {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(secretSha256, "secretSha256");
      audiences = Set.copyOf(audiences);
    }
  }

  public IdentitySettings {
    Objects.requireNonNull(issuer, "issuer");
    audiences = List.copyOf(audiences);
    publicClients = Set.copyOf(publicClients);
    serviceClients = Map.copyOf(serviceClients);
    if (!publicClients.contains(defaultClient)) {
      throw new IllegalArgumentException("default client must be one of the public clients");
    }
    if (accessTokenTtl.compareTo(Duration.ofMinutes(15)) > 0) {
      throw new IllegalArgumentException("access tokens live at most 15 minutes (services reject longer ones)");
    }
    if (refreshIdle.compareTo(refreshAbsolute) > 0) {
      throw new IllegalArgumentException("refresh idle window must not exceed the absolute limit");
    }
  }
}
