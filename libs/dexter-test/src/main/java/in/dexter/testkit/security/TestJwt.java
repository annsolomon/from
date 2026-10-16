package in.dexter.testkit.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import in.dexter.security.jwt.JwksCache;
import in.dexter.security.jwt.JwksSource;
import in.dexter.security.jwt.JwtSettings;
import in.dexter.security.jwt.JwtVerifier;
import in.dexter.security.jwt.TokenClaims;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test identity provider (MASTER_PLAN 10, P02 step 4): ES256 keys, a live JWKS, and a token builder
 * for every edge case (expired, wrong audience, unknown kid, {@code alg=none}, HS256 key confusion,
 * embedded JWK, rotation). Test code only; tokens it mints are rejected by real services because
 * their issuer's keys are not in identity's JWKS.
 *
 * <pre>{@code
 * TestJwt idp = TestJwt.create();
 * JwtVerifier verifier = idp.verifier("ledger", clock);
 * String token = idp.user(accountId).audience("ledger").sign();
 * }</pre>
 */
public final class TestJwt {
  public static final String ISSUER = "https://identity.dexter.test";
  public static final String CLIENT_ID = "dexter-app";

  private final List<ECKey> published = new CopyOnWriteArrayList<>();
  private final AtomicInteger kidSeq = new AtomicInteger();
  private final AtomicInteger fetches = new AtomicInteger();
  private volatile ECKey current;
  private volatile boolean failing;

  private TestJwt() {
    current = newKey();
    published.add(current);
  }

  public static TestJwt create() {
    return new TestJwt();
  }

  /** A fresh ES256 key with kid {@code test-<n>} (not published). */
  public ECKey newKey() {
    return newKey("test-" + kidSeq.incrementAndGet());
  }

  public static ECKey newKey(String kid) {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      KeyPair kp = g.generateKeyPair();
      return new ECKey.Builder(Curve.P_256, (ECPublicKey) kp.getPublic())
          .privateKey((ECPrivateKey) kp.getPrivate())
          .keyID(kid)
          .keyUse(KeyUse.SIGNATURE)
          .algorithm(JWSAlgorithm.ES256)
          .build();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Key rotation (MASTER_PLAN 7.1): a new key becomes the signing key; the old one stays published. */
  public ECKey rotate() {
    ECKey next = newKey();
    published.add(next);
    current = next;
    return next;
  }

  /** Removes a key from the JWKS (retired). */
  public void retire(String kid) {
    published.removeIf(k -> k.getKeyID().equals(kid));
  }

  public ECKey currentKey() {
    return current;
  }

  /** The public JWKS as identity would serve it. */
  public JWKSet publicJwks() {
    List<JWK> keys = new ArrayList<>();
    for (ECKey k : published) {
      keys.add(k.toPublicJWK());
    }
    return new JWKSet(keys);
  }

  /** Live in-memory JWKS endpoint; counts fetches; {@link #failJwks(boolean)} simulates an outage. */
  public JwksSource source() {
    return () -> {
      fetches.incrementAndGet();
      if (failing) {
        throw new java.io.IOException("simulated JWKS outage");
      }
      return publicJwks();
    };
  }

  public void failJwks(boolean fail) {
    this.failing = fail;
  }

  public int jwksFetches() {
    return fetches.get();
  }

  /** A verifier for {@code audience} backed by {@link #source()} with the default cache settings. */
  public JwtVerifier verifier(String audience, Clock clock) {
    return new JwtVerifier(JwtSettings.of(ISSUER, audience), JwksCache.withDefaults(source(), clock), clock);
  }

  public Token user(UUID accountId) {
    return new Token(accountId.toString())
        .claim(TokenClaims.SESSION_ID, UUID.randomUUID().toString())
        .claim(TokenClaims.ROLES, List.of("USER"))
        .claim(TokenClaims.AMR, List.of("pwd"));
  }

  /** An account with admin roles; set permissions with {@link Token#permissions(String...)}. */
  public Token admin(UUID accountId, String... roles) {
    return new Token(accountId.toString())
        .claim(TokenClaims.SESSION_ID, UUID.randomUUID().toString())
        .claim(TokenClaims.ROLES, List.of(roles))
        .claim(TokenClaims.AMR, List.of("pwd", "mfa"))
        .claim(TokenClaims.MFA_ENROLLED, true);
  }

  /** A client-credentials token of service {@code name}. */
  public Token service(String name) {
    return new Token(TokenClaims.SERVICE_SUBJECT_PREFIX + name).claim(TokenClaims.CLIENT_ID, name);
  }

  /** Builder; defaults: issued now, 10 min TTL, typ at+jwt, signed with the current key. */
  public final class Token {
    private final Map<String, Object> claims = new LinkedHashMap<>();
    private final List<String> audience = new ArrayList<>();
    private String subject;
    private String issuer = ISSUER;
    private Instant issuedAt = Instant.now();
    private Duration ttl = Duration.ofMinutes(10);
    private Instant authTime;
    private ECKey signingKey;
    private String kidOverride;
    private String typ = JwtSettings.ACCESS_TOKEN_TYPE;
    private JWK embeddedJwk;

    private Token(String subject) {
      this.subject = subject;
      claims.put(TokenClaims.CLIENT_ID, CLIENT_ID);
    }

    public Token claim(String name, Object value) {
      if (value == null) {
        claims.remove(name);
      } else {
        claims.put(name, value);
      }
      return this;
    }

    public Token audience(String... aud) {
      audience.clear();
      audience.addAll(List.of(aud));
      return this;
    }

    public Token subject(String sub) {
      this.subject = sub;
      return this;
    }

    public Token issuer(String iss) {
      this.issuer = iss;
      return this;
    }

    public Token issuedAt(Instant iat) {
      this.issuedAt = iat;
      return this;
    }

    public Token ttl(Duration ttl) {
      this.ttl = ttl;
      return this;
    }

    public Token authTime(Instant t) {
      this.authTime = t;
      return this;
    }

    public Token amr(String... values) {
      return claim(TokenClaims.AMR, List.of(values));
    }

    public Token mfaEnrolled(boolean enrolled) {
      return claim(TokenClaims.MFA_ENROLLED, enrolled);
    }

    public Token permissions(String... perms) {
      return claim(TokenClaims.PERMISSIONS, List.of(perms));
    }

    public Token scope(String scope) {
      return claim(TokenClaims.SCOPE, scope);
    }

    /** Sign with a key that is not (necessarily) published. */
    public Token signWith(ECKey key) {
      this.signingKey = key;
      return this;
    }

    /** Put another kid in the header (e.g. a published kid while signing with a foreign key). */
    public Token kid(String kid) {
      this.kidOverride = kid;
      return this;
    }

    /** {@code typ} header; null removes it. */
    public Token typ(String typ) {
      this.typ = typ;
      return this;
    }

    /** Embed a JWK in the header (attack: "trust the key in the token"). */
    public Token embedJwk(JWK jwk) {
      this.embeddedJwk = jwk;
      return this;
    }

    public JWTClaimsSet claimsSet() {
      JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
          .issuer(issuer)
          .subject(subject)
          .audience(List.copyOf(audience))
          .issueTime(Date.from(issuedAt))
          .expirationTime(Date.from(issuedAt.plus(ttl)))
          .jwtID(UUID.randomUUID().toString());
      Instant at = authTime != null ? authTime : issuedAt;
      if (!subject.startsWith(TokenClaims.SERVICE_SUBJECT_PREFIX)) {
        b.claim(TokenClaims.AUTH_TIME, at.getEpochSecond());
      }
      claims.forEach(b::claim);
      return b.build();
    }

    /** ES256-signed compact token. */
    public String sign() {
      ECKey key = signingKey != null ? signingKey : current;
      JWSHeader.Builder h = new JWSHeader.Builder(JWSAlgorithm.ES256)
          .keyID(kidOverride != null ? kidOverride : key.getKeyID());
      if (typ != null) {
        h.type(new JOSEObjectType(typ));
      }
      if (embeddedJwk != null) {
        h.jwk(embeddedJwk.toPublicJWK());
      }
      SignedJWT jwt = new SignedJWT(h.build(), claimsSet());
      try {
        jwt.sign(new ECDSASigner(key));
      } catch (JOSEException e) {
        throw new IllegalStateException(e);
      }
      return jwt.serialize();
    }

    /** {@code alg=none}: header and payload, empty signature. */
    public String unsigned() {
      String header = Base64URL.encode("{\"alg\":\"none\",\"typ\":\"" + JwtSettings.ACCESS_TOKEN_TYPE + "\"}"
          .getBytes(StandardCharsets.UTF_8)).toString();
      String payload = Base64URL.encode(claimsSet().toString().getBytes(StandardCharsets.UTF_8)).toString();
      return header + "." + payload + ".";
    }

    /**
     * HS256 signed with the published public key's JSON as the HMAC secret: the classic
     * algorithm-confusion attack against verifiers that pick the algorithm from the token.
     */
    public String hs256WithPublicKey() {
      ECKey key = signingKey != null ? signingKey : current;
      byte[] secret = key.toPublicJWK().toJSONString().getBytes(StandardCharsets.UTF_8);
      JWSHeader h = new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(key.getKeyID())
          .type(new JOSEObjectType(JwtSettings.ACCESS_TOKEN_TYPE)).build();
      SignedJWT jwt = new SignedJWT(h, claimsSet());
      try {
        jwt.sign(new MACSigner(secret));
      } catch (JOSEException e) {
        throw new IllegalStateException(e);
      }
      return jwt.serialize();
    }
  }
}
