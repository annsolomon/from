package in.dexter.testkit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.ECKey;
import in.dexter.core.time.MutableClock;
import in.dexter.security.AuthContext;
import in.dexter.security.AuthContext.Kind;
import in.dexter.security.jwt.JwksCache;
import in.dexter.security.jwt.JwtSettings;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.security.jwt.TokenClaims;
import in.dexter.security.jwt.TokenRejectedException;
import in.dexter.security.jwt.TokenRejectedException.Reason;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P02 JWT tests (MASTER_PLAN 13, P02): expired, wrong aud, wrong kid, {@code none} alg, key rotation,
 * plus key confusion, embedded keys, typ, lifetime and JWKS outage behaviour. Plain unit tests: no
 * Docker, no network.
 */
class JwtVerifierTest {
  static final UUID ACCOUNT = UUID.fromString("0192f0c8-7e2a-7b3c-8d4e-5f6a7b8c9d0e");
  static final Instant T0 = Instant.parse("2026-09-27T10:00:00Z");

  TestJwt idp;
  MutableClock clock;
  JwtVerifier verifier;

  @BeforeEach
  void setUp() {
    idp = TestJwt.create();
    clock = new MutableClock(T0);
    verifier = idp.verifier("ledger", clock);
  }

  TestJwt.Token user() {
    return idp.user(ACCOUNT).audience("ledger").issuedAt(clock.instant());
  }

  static void rejected(ThrowingCallable call, Reason reason) {
    assertThatThrownBy(call).isInstanceOf(TokenRejectedException.class)
        .satisfies(e -> assertThat(((TokenRejectedException) e).reason()).isEqualTo(reason));
  }

  @Test
  void validUserTokenBecomesAuthContext() {
    String token = user().claim(TokenClaims.TZ, "Asia/Kolkata").claim(TokenClaims.TIER, "PRO").scope("read write").sign();
    AuthContext ctx = verifier.verify(token);
    assertThat(ctx.kind()).isEqualTo(Kind.USER);
    assertThat(ctx.accountId()).isEqualTo(ACCOUNT);
    assertThat(ctx.tenant().accountId()).isEqualTo(ACCOUNT);
    assertThat(ctx.tz()).isEqualTo(ZoneId.of("Asia/Kolkata"));
    assertThat(ctx.tier()).isEqualTo("PRO");
    assertThat(ctx.scopes()).containsExactlyInAnyOrder("read", "write");
    assertThat(ctx.sessionId()).isNotBlank();
    assertThat(ctx.authTime()).isEqualTo(T0);
    assertThat(ctx.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
  }

  @Test
  void adminAndServiceKinds() {
    AuthContext admin = verifier.verify(idp.admin(ACCOUNT, "SUPPORT").permissions("expense.read")
        .audience("ledger").issuedAt(T0).sign());
    assertThat(admin.kind()).isEqualTo(Kind.ADMIN);
    assertThat(admin.hasPermission("expense.read")).isTrue();
    assertThat(admin.mfaEnrolled()).isTrue();

    AuthContext svc = verifier.verify(idp.service("reco").audience("ledger").issuedAt(T0).sign());
    assertThat(svc.kind()).isEqualTo(Kind.SERVICE);
    assertThat(svc.service()).isEqualTo("reco");
    assertThat(svc.accountId()).isNull();
  }

  @Test
  void expiredTokenIsRejectedAfterSkew() {
    String token = user().sign();
    clock.advance(Duration.ofMinutes(10).plusSeconds(29));
    assertThat(verifier.verify(token).accountId()).isEqualTo(ACCOUNT); // within 30 s skew
    clock.advance(Duration.ofSeconds(1));
    rejected(() -> verifier.verify(token), Reason.EXPIRED);
  }

  @Test
  void tokenFromTheFutureIsRejected() {
    rejected(() -> verifier.verify(user().issuedAt(T0.plusSeconds(120)).sign()), Reason.NOT_YET_VALID);
  }

  @Test
  void overLongLifetimeIsRejected() {
    rejected(() -> verifier.verify(user().ttl(Duration.ofHours(24)).sign()), Reason.LIFETIME);
  }

  @Test
  void wrongAudienceIsRejected() {
    rejected(() -> verifier.verify(idp.user(ACCOUNT).audience("billing").issuedAt(T0).sign()), Reason.AUDIENCE);
    rejected(() -> verifier.verify(idp.user(ACCOUNT).issuedAt(T0).sign()), Reason.AUDIENCE);
  }

  @Test
  void wrongIssuerIsRejected() {
    rejected(() -> verifier.verify(user().issuer("https://evil.example").sign()), Reason.ISSUER);
  }

  @Test
  void unknownKidIsRejected() {
    ECKey foreign = TestJwt.newKey("attacker-1");
    rejected(() -> verifier.verify(user().signWith(foreign).sign()), Reason.KID_UNKNOWN);
  }

  @Test
  void publishedKidWithForeignSignatureIsRejected() {
    ECKey foreign = TestJwt.newKey("attacker-2");
    String token = user().signWith(foreign).kid(idp.currentKey().getKeyID()).sign();
    rejected(() -> verifier.verify(token), Reason.SIGNATURE);
  }

  @Test
  void tamperedPayloadIsRejected() {
    String token = user().sign();
    String[] parts = token.split("\\.");
    String evil = TestJwt.create().user(UUID.randomUUID()).audience("ledger").issuedAt(T0).sign().split("\\.")[1];
    rejected(() -> verifier.verify(parts[0] + "." + evil + "." + parts[2]), Reason.SIGNATURE);
  }

  @Test
  void algNoneIsRejected() {
    assertThatThrownBy(() -> verifier.verify(user().unsigned()))
        .isInstanceOf(TokenRejectedException.class)
        .satisfies(e -> assertThat(((TokenRejectedException) e).reason()).isIn(Reason.MALFORMED, Reason.ALGORITHM));
  }

  @Test
  void hmacKeyConfusionIsRejected() {
    rejected(() -> verifier.verify(user().hs256WithPublicKey()), Reason.ALGORITHM);
  }

  @Test
  void embeddedJwkIsRejected() {
    ECKey foreign = TestJwt.newKey("attacker-3");
    rejected(() -> verifier.verify(user().signWith(foreign).embedJwk(foreign).sign()), Reason.HEADER);
  }

  @Test
  void typMustBeAccessToken() {
    rejected(() -> verifier.verify(user().typ(null).sign()), Reason.TYPE);
    rejected(() -> verifier.verify(user().typ("JWT").sign()), Reason.TYPE);
    assertThat(verifier.verify(user().typ("application/at+jwt").sign()).accountId()).isEqualTo(ACCOUNT);
  }

  @Test
  void missingKidIsRejected() {
    rejected(() -> verifier.verify(user().kid("").sign()), Reason.KID_MISSING);
  }

  @Test
  void requiredClaimsAndGarbage() {
    rejected(() -> verifier.verify(user().claim(TokenClaims.SESSION_ID, null).sign()), Reason.CLAIMS);
    rejected(() -> verifier.verify(user().claim(TokenClaims.CLIENT_ID, null).sign()), Reason.CLAIMS);
    rejected(() -> verifier.verify(user().subject("not-a-uuid").sign()), Reason.CLAIMS);
    rejected(() -> verifier.verify(user().claim(TokenClaims.TZ, "Mars/Olympus").sign()), Reason.CLAIMS);
    rejected(() -> verifier.verify(user().permissions("expense.delete").sign()), Reason.CLAIMS); // perms without admin role
    rejected(() -> verifier.verify(idp.service("Bad_Name").audience("ledger").issuedAt(T0).sign()), Reason.CLAIMS);
    rejected(() -> verifier.verify("not.a.jwt"), Reason.MALFORMED);
    rejected(() -> verifier.verify(""), Reason.MALFORMED);
    rejected(() -> verifier.verify(null), Reason.MALFORMED);
    rejected(() -> verifier.verify("a".repeat(JwtVerifier.MAX_TOKEN_CHARS + 1)), Reason.MALFORMED);
  }

  @Test
  void keyRotationIsPickedUpThroughUnknownKidRefetch() {
    String oldToken = user().sign();
    assertThat(verifier.verify(oldToken)).isNotNull(); // JWKS fetched once, cached
    assertThat(idp.jwksFetches()).isEqualTo(1);

    idp.rotate();
    clock.advance(Duration.ofSeconds(11)); // past the refetch rate limit, well inside the 5 min TTL
    String newToken = user().sign();
    assertThat(verifier.verify(newToken).accountId()).isEqualTo(ACCOUNT); // unknown kid -> refetch
    assertThat(idp.jwksFetches()).isEqualTo(2);
    assertThat(verifier.verify(oldToken).accountId()).isEqualTo(ACCOUNT); // old key still published

    String oldKid = keyIdOf(oldToken);
    idp.retire(oldKid);
    clock.advance(Duration.ofMinutes(5)); // TTL over -> next call refetches without the retired key
    String oldKeyFreshToken = user().signWith(TestJwt.newKey(oldKid)).sign(); // same kid, different key
    rejected(() -> verifier.verify(oldKeyFreshToken), Reason.KID_UNKNOWN);
  }

  @Test
  void unknownKidRefetchIsRateLimited() {
    verifier.verify(user().sign());
    int before = idp.jwksFetches();
    clock.advance(Duration.ofSeconds(11));
    for (int i = 0; i < 50; i++) {
      ECKey k = TestJwt.newKey("flood-" + i);
      rejected(() -> verifier.verify(user().signWith(k).sign()), Reason.KID_UNKNOWN);
    }
    assertThat(idp.jwksFetches() - before).isEqualTo(1);
  }

  @Test
  void staleKeysSurviveAnOutageUntilMaxStale() {
    JwtVerifier v = new JwtVerifier(JwtSettings.of(TestJwt.ISSUER, "ledger"),
        new JwksCache(idp.source(), clock, Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ofMinutes(30)), clock);
    v.verify(user().sign());
    idp.failJwks(true);
    clock.advance(Duration.ofMinutes(20)); // TTL over, fetch fails, keys still usable
    assertThat(v.verify(user().sign()).accountId()).isEqualTo(ACCOUNT);
    clock.advance(Duration.ofMinutes(11)); // beyond max-stale
    rejected(() -> v.verify(user().sign()), Reason.JWKS_UNAVAILABLE);
    idp.failJwks(false);
    clock.advance(Duration.ofSeconds(11));
    assertThat(v.verify(user().sign()).accountId()).isEqualTo(ACCOUNT); // recovers
  }

  @Test
  void firstFetchFailureIsUnavailableNotUnknownKid() {
    idp.failJwks(true);
    rejected(() -> verifier.verify(user().sign()), Reason.JWKS_UNAVAILABLE);
  }

  private static String keyIdOf(String token) {
    String header = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0]),
        java.nio.charset.StandardCharsets.UTF_8);
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"kid\":\"([^\"]+)\"").matcher(header);
    assertThat(m.find()).isTrue();
    return m.group(1);
  }
}
