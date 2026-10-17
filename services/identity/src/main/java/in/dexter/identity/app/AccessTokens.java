package in.dexter.identity.app;

import in.dexter.core.id.Ids;
import in.dexter.core.tenant.Tenant;
import in.dexter.security.jwt.TokenClaims;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Builds access tokens exactly as dexter-security's {@code JwtVerifier} expects them (A-18). */
public final class AccessTokens {
  public static final String USER_SCOPE = "user";
  public static final String SERVICE_SCOPE = "service";

  private final SigningKeyService keys;
  private final IdentitySettings settings;
  private final Clock clock;

  public AccessTokens(SigningKeyService keys, IdentitySettings settings, Clock clock) {
    this.keys = keys;
    this.settings = settings;
    this.clock = clock;
  }

  /** A signed user token and its lifetime in seconds. */
  public record Issued(String token, long expiresIn) {}

  public Issued forUser(UUID accountId, UUID sessionId, String clientId, List<String> amr, Instant authTime, String tz,
      String tier, boolean mfaEnrolled) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    TokenClaimsSpec spec = new TokenClaimsSpec(accountId.toString(), settings.audiences(), clientId,
        sessionId.toString(), USER_SCOPE, List.of(Tenant.USER), List.copyOf(amr), authTime, mfaEnrolled, tz, tier,
        settings.accessTokenTtl(), settings.issuer(), now, Ids.newId().toString());
    return new Issued(keys.sign(spec), settings.accessTokenTtl().toSeconds());
  }

  public Issued forService(String service, String audience) {
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    Duration ttl = settings.serviceTokenTtl();
    TokenClaimsSpec spec = new TokenClaimsSpec(TokenClaims.SERVICE_SUBJECT_PREFIX + service, List.of(audience), service,
        null, SERVICE_SCOPE, null, null, null, null, null, null, ttl, settings.issuer(), now, Ids.newId().toString());
    return new Issued(keys.sign(spec), ttl.toSeconds());
  }
}
