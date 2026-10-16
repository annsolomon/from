package in.dexter.security.autoconfigure;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code dexter.security.*}. Setting {@code jwks-uri} turns request authentication on.
 *
 * @param jwksUri identity's {@code /.well-known/jwks.json}
 * @param issuer exact {@code iss} identity signs with (required with {@code jwks-uri})
 * @param audience this service's name in {@code aud}; defaults to {@code spring.application.name}
 * @param publicPaths paths that skip authentication when the handler has no security annotations
 */
@ConfigurationProperties("dexter.security")
public record DexterSecurityProperties(
    @Nullable URI jwksUri,
    @Nullable String issuer,
    @Nullable String audience,
    @DefaultValue("at+jwt") String tokenType,
    @DefaultValue("30s") Duration clockSkew,
    @DefaultValue("15m") Duration maxTokenLifetime,
    @DefaultValue("10m") Duration stepUpMaxAge,
    @DefaultValue("5m") Duration jwksTtl,
    @DefaultValue("10s") Duration jwksMinRefetchInterval,
    @DefaultValue("1h") Duration jwksMaxStale,
    @DefaultValue({"/_dev/**", "/error"}) List<String> publicPaths,
    @DefaultValue Client client) {

  /**
   * {@code dexter.security.client.*}: client-credentials for calling other services. Setting
   * {@code token-uri} creates a {@code ServiceTokenClient}.
   */
  public record Client(
      @Nullable URI tokenUri,
      @Nullable String clientId,
      @Nullable String clientSecret,
      @DefaultValue("60s") Duration refreshSkew) {}
}
