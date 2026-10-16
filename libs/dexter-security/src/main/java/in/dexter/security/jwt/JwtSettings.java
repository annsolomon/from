package in.dexter.security.jwt;

import java.time.Duration;
import java.util.Objects;

/**
 * What a service accepts. {@code audience} is this service's name: a token for another service is
 * rejected even when its signature is valid (MASTER_PLAN 7.2).
 *
 * @param issuer exact {@code iss} value identity signs with
 * @param audience this service; must appear in {@code aud}
 * @param tokenType required {@code typ} header ({@code at+jwt})
 * @param clockSkew tolerance for {@code exp}, {@code nbf} and {@code iat}
 * @param maxLifetime upper bound for {@code exp - iat}; defends against over-long tokens
 */
public record JwtSettings(String issuer, String audience, String tokenType, Duration clockSkew, Duration maxLifetime) {
  public static final String ACCESS_TOKEN_TYPE = "at+jwt";

  public JwtSettings {
    Objects.requireNonNull(issuer, "issuer");
    Objects.requireNonNull(audience, "audience");
    Objects.requireNonNull(tokenType, "tokenType");
    Objects.requireNonNull(clockSkew, "clockSkew");
    Objects.requireNonNull(maxLifetime, "maxLifetime");
    if (issuer.isBlank() || audience.isBlank()) {
      throw new IllegalArgumentException("issuer and audience must not be blank");
    }
    if (clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(5)) > 0) {
      throw new IllegalArgumentException("clockSkew must be within 0..5m");
    }
    if (maxLifetime.isNegative() || maxLifetime.isZero()) {
      throw new IllegalArgumentException("maxLifetime must be positive");
    }
  }

  /** Defaults: {@code at+jwt}, 30 s skew, 15 min maximum lifetime (tokens live 10 min). */
  public static JwtSettings of(String issuer, String audience) {
    return new JwtSettings(issuer, audience, ACCESS_TOKEN_TYPE, Duration.ofSeconds(30), Duration.ofMinutes(15));
  }
}
