package in.dexter.security.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import in.dexter.core.time.Clocks;
import in.dexter.security.AuthContext;
import in.dexter.security.AuthContext.Kind;
import in.dexter.security.jwt.TokenRejectedException.Reason;
import java.text.ParseException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Validates an access token and turns it into an {@link AuthContext} (MASTER_PLAN 7.1/7.2).
 * Thread-safe; one instance per service.
 *
 * <p>Order of checks: size and structure, header (algorithm allowlist = ES256 only, so {@code none}
 * and HMAC key-confusion tokens never reach a verifier; no embedded or remote keys; {@code typ}),
 * signature with the key named by {@code kid} from our JWKS, then claims ({@code iss}, {@code aud},
 * {@code exp}/{@code nbf}/{@code iat} with skew, maximum lifetime, required claims).
 */
public final class JwtVerifier {
  /** Tokens are ~1 KB; anything this large is not ours. */
  public static final int MAX_TOKEN_CHARS = 8 * 1024;
  private static final Pattern SERVICE_NAME = Pattern.compile("[a-z][a-z0-9-]{1,31}");
  private static final String DEFAULT_TIER = "FREE";

  private final JwtSettings settings;
  private final JwksCache keys;
  private final Clock clock;

  public JwtVerifier(JwtSettings settings, JwksCache keys, Clock clock) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.keys = Objects.requireNonNull(keys, "keys");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public JwtSettings settings() {
    return settings;
  }

  /**
   * @throws TokenRejectedException when the token is not acceptable; never returns null
   */
  public AuthContext verify(@Nullable String token) {
    if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_CHARS) {
      throw new TokenRejectedException(Reason.MALFORMED, "empty or oversized token");
    }
    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(token);
    } catch (ParseException e) {
      throw new TokenRejectedException(Reason.MALFORMED, "not a compact JWS");
    }
    JWSHeader h = jwt.getHeader();
    if (!JWSAlgorithm.ES256.equals(h.getAlgorithm())) {
      throw new TokenRejectedException(Reason.ALGORITHM, "algorithm not allowed");
    }
    if (h.getJWK() != null || h.getJWKURL() != null || h.getX509CertURL() != null || h.getX509CertChain() != null) {
      throw new TokenRejectedException(Reason.HEADER, "embedded or remote keys are not accepted");
    }
    if (!typeMatches(h.getType())) {
      throw new TokenRejectedException(Reason.TYPE, "wrong or missing typ");
    }
    String kid = h.getKeyID();
    if (kid == null || kid.isBlank()) {
      throw new TokenRejectedException(Reason.KID_MISSING, "kid missing");
    }
    JWSVerifier verifier = keys.verifier(kid);
    boolean valid;
    try {
      valid = jwt.verify(verifier);
    } catch (JOSEException e) {
      throw new TokenRejectedException(Reason.SIGNATURE, "signature check failed");
    }
    if (!valid) {
      throw new TokenRejectedException(Reason.SIGNATURE, "bad signature");
    }
    JWTClaimsSet c;
    try {
      c = jwt.getJWTClaimsSet();
    } catch (ParseException e) {
      throw new TokenRejectedException(Reason.MALFORMED, "claims are not a JSON object");
    }
    return claims(c);
  }

  private boolean typeMatches(@Nullable JOSEObjectType typ) {
    if (typ == null || typ.getType() == null) {
      return false;
    }
    String t = typ.getType().toLowerCase(Locale.ROOT);
    String want = settings.tokenType().toLowerCase(Locale.ROOT);
    return t.equals(want) || t.equals("application/" + want);
  }

  private AuthContext claims(JWTClaimsSet c) {
    Instant now = clock.instant();
    Duration skew = settings.clockSkew();
    if (!settings.issuer().equals(c.getIssuer())) {
      throw new TokenRejectedException(Reason.ISSUER, "unexpected issuer");
    }
    List<String> aud = c.getAudience();
    if (aud == null || !aud.contains(settings.audience())) {
      throw new TokenRejectedException(Reason.AUDIENCE, "token is not for this service");
    }
    Instant exp = instant(c.getExpirationTime());
    Instant iat = instant(c.getIssueTime());
    if (exp == null || iat == null) {
      throw new TokenRejectedException(Reason.CLAIMS, "exp and iat are required");
    }
    if (!now.isBefore(exp.plus(skew))) {
      throw new TokenRejectedException(Reason.EXPIRED, "token expired");
    }
    Instant nbf = instant(c.getNotBeforeTime());
    if (iat.isAfter(now.plus(skew)) || (nbf != null && nbf.isAfter(now.plus(skew)))) {
      throw new TokenRejectedException(Reason.NOT_YET_VALID, "token not valid yet");
    }
    if (Duration.between(iat, exp).compareTo(settings.maxLifetime()) > 0 || exp.isBefore(iat)) {
      throw new TokenRejectedException(Reason.LIFETIME, "token lifetime out of bounds");
    }
    try {
      return context(c, exp, iat);
    } catch (ParseException | IllegalArgumentException | DateTimeException e) {
      throw new TokenRejectedException(Reason.CLAIMS, "invalid claim: " + e.getClass().getSimpleName());
    }
  }

  private AuthContext context(JWTClaimsSet c, Instant exp, Instant iat) throws ParseException {
    String sub = required(c.getSubject(), "sub");
    String jti = required(c.getJWTID(), "jti");
    String clientId = required(c.getStringClaim(TokenClaims.CLIENT_ID), TokenClaims.CLIENT_ID);
    Set<String> roles = set(c.getStringListClaim(TokenClaims.ROLES));
    Set<String> perms = set(c.getStringListClaim(TokenClaims.PERMISSIONS));
    Set<String> amr = set(c.getStringListClaim(TokenClaims.AMR));
    String scope = c.getStringClaim(TokenClaims.SCOPE);
    Set<String> scopes = scope == null || scope.isBlank()
        ? Set.of() : Arrays.stream(scope.trim().split(" +")).collect(Collectors.toUnmodifiableSet());
    String tzName = c.getStringClaim(TokenClaims.TZ);
    ZoneId tz = tzName == null ? Clocks.DEFAULT_ZONE : ZoneId.of(tzName);
    String tier = c.getStringClaim(TokenClaims.TIER);
    Instant authTime = instant(c.getDateClaim(TokenClaims.AUTH_TIME));
    Boolean mfa = c.getBooleanClaim(TokenClaims.MFA_ENROLLED);
    Set<String> audiences = Set.copyOf(c.getAudience());

    if (sub.startsWith(TokenClaims.SERVICE_SUBJECT_PREFIX)) {
      String service = sub.substring(TokenClaims.SERVICE_SUBJECT_PREFIX.length());
      if (!SERVICE_NAME.matcher(service).matches()) {
        throw new IllegalArgumentException("bad service subject");
      }
      if (!roles.isEmpty() || !perms.isEmpty()) {
        throw new IllegalArgumentException("service tokens carry no roles or permissions");
      }
      return new AuthContext(Kind.SERVICE, sub, null, service, null, clientId, audiences, scopes, Set.of(), Set.of(),
          amr, authTime, false, tz, tier == null ? DEFAULT_TIER : tier, jti, iat, exp);
    }
    UUID account = UUID.fromString(sub);
    String sid = required(c.getStringClaim(TokenClaims.SESSION_ID), TokenClaims.SESSION_ID);
    boolean admin = roles.stream().anyMatch(AuthContext.ADMIN_ROLES::contains);
    if (!admin && !perms.isEmpty()) {
      throw new IllegalArgumentException("permissions without an admin role");
    }
    return new AuthContext(admin ? Kind.ADMIN : Kind.USER, sub, account, null, sid, clientId, audiences, scopes,
        roles, perms, amr, authTime, Boolean.TRUE.equals(mfa), tz, tier == null ? DEFAULT_TIER : tier, jti, iat, exp);
  }

  private static String required(@Nullable String v, String name) {
    if (v == null || v.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return v;
  }

  private static Set<String> set(@Nullable List<String> values) {
    if (values == null) {
      return Set.of();
    }
    for (String v : values) {
      if (v == null || v.isBlank()) {
        throw new IllegalArgumentException("empty value in a list claim");
      }
    }
    return Set.copyOf(values);
  }

  private static @Nullable Instant instant(@Nullable Date d) {
    return d == null ? null : d.toInstant();
  }
}
