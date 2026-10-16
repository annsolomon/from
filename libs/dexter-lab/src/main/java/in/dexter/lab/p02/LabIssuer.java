package in.dexter.lab.p02;

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
import in.dexter.core.error.ValidationException;
import in.dexter.security.jwt.JwksSource;
import in.dexter.security.jwt.JwtSettings;
import in.dexter.security.jwt.TokenClaims;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The lab's stand-in for identity (P04 issues real tokens): ES256 keys with rotation and retirement, an in-memory
 * JWKS that counts fetches, and tokens with one chosen defect each, so the JWT playground shows exactly what the real
 * {@code JwtVerifier} accepts and rejects (A-18 token contract).
 */
public final class LabIssuer {
  public static final String ISSUER = "https://identity.dexter.lab";
  public static final String AUDIENCE = "lab";
  public static final String CLIENT_ID = "dexter-trail";
  public static final UUID ASHA = UUID.fromString("0192a5a1-0000-7000-8000-000000000001");
  public static final UUID RAVI = UUID.fromString("0192a5a1-0000-7000-8000-000000000002");
  public static final List<String> PERSONAS = List.of("asha", "ravi", "svc-reco");
  /** Defect → what the verifier answers (shown on the trail page). */
  public static final Map<String, String> DEFECTS = Map.ofEntries(
      Map.entry("none", "accepted"),
      Map.entry("expired", "EXPIRED"),
      Map.entry("not-yet-valid", "NOT_YET_VALID"),
      Map.entry("lifetime-too-long", "LIFETIME"),
      Map.entry("wrong-audience", "AUDIENCE"),
      Map.entry("wrong-issuer", "ISSUER"),
      Map.entry("wrong-typ", "TYPE"),
      Map.entry("alg-none", "MALFORMED or ALGORITHM"),
      Map.entry("hs256-confusion", "ALGORITHM"),
      Map.entry("unknown-kid", "KID_UNKNOWN"),
      Map.entry("embedded-jwk", "HEADER"),
      Map.entry("bad-signature", "SIGNATURE"));

  private final List<ECKey> published = new CopyOnWriteArrayList<>();
  private final AtomicInteger kidSeq = new AtomicInteger();
  private final AtomicLong fetches = new AtomicLong();
  private volatile ECKey current;

  public LabIssuer() {
    current = newKey("lab-" + kidSeq.incrementAndGet());
    published.add(current);
  }

  static ECKey newKey(String kid) {
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

  /** The public JWKS, as identity's {@code /.well-known/jwks.json} would serve it. */
  public JWKSet publicJwks() {
    List<JWK> keys = new ArrayList<>();
    for (ECKey k : published) {
      keys.add(k.toPublicJWK());
    }
    return new JWKSet(keys);
  }

  /** In-memory JWKS endpoint for the verifier's {@code JwksCache}; counts fetches. */
  public JwksSource source() {
    return () -> {
      fetches.incrementAndGet();
      return publicJwks();
    };
  }

  public long fetches() {
    return fetches.get();
  }

  public String currentKid() {
    return current.getKeyID();
  }

  public List<String> publishedKids() {
    return published.stream().map(ECKey::getKeyID).toList();
  }

  /** MASTER_PLAN 7.1 rotation: a new key signs from now on; the old ones stay published until retired. */
  public synchronized String rotate() {
    ECKey next = newKey("lab-" + kidSeq.incrementAndGet());
    published.add(next);
    current = next;
    return next.getKeyID();
  }

  /** Unpublishes every key but the current one. Verifiers drop them at their next JWKS refresh (TTL 5 min). */
  public synchronized List<String> retireOld() {
    List<String> retired = new ArrayList<>();
    for (ECKey k : published) {
      if (!k.getKeyID().equals(current.getKeyID())) {
        published.remove(k);
        retired.add(k.getKeyID());
      }
    }
    return retired;
  }

  /** Account id of a user persona; null for the service persona. */
  public static UUID accountOf(String persona) {
    return switch (persona) {
      case "asha" -> ASHA;
      case "ravi" -> RAVI;
      default -> null;
    };
  }

  /** A token for {@code persona} (asha, ravi, svc-reco) with {@code defect} (see {@link #DEFECTS}). */
  public String token(String persona, String defect) {
    String p = persona == null ? "asha" : persona;
    String d = defect == null ? "none" : defect;
    if (!PERSONAS.contains(p)) {
      throw ValidationException.of("persona", "UNKNOWN_PERSONA", "persona must be one of " + PERSONAS);
    }
    if (!DEFECTS.containsKey(d)) {
      throw ValidationException.of("defect", "UNKNOWN_DEFECT", "defect must be one of " + DEFECTS.keySet());
    }
    Instant now = Instant.now();
    Instant iat = switch (d) {
      case "expired" -> now.minus(Duration.ofMinutes(20));
      case "not-yet-valid" -> now.plus(Duration.ofMinutes(5));
      default -> now;
    };
    Duration ttl = d.equals("lifetime-too-long") ? Duration.ofMinutes(60) : Duration.ofMinutes(10);
    JWTClaimsSet.Builder c = new JWTClaimsSet.Builder()
        .issuer(d.equals("wrong-issuer") ? "https://evil.example" : ISSUER)
        .audience(List.of(d.equals("wrong-audience") ? "reco" : AUDIENCE))
        .issueTime(Date.from(iat))
        .expirationTime(Date.from(iat.plus(ttl)))
        .jwtID(UUID.randomUUID().toString());
    UUID account = accountOf(p);
    if (account != null) {
      c.subject(account.toString())
          .claim(TokenClaims.CLIENT_ID, CLIENT_ID)
          .claim(TokenClaims.SESSION_ID, UUID.randomUUID().toString())
          .claim(TokenClaims.ROLES, List.of("USER"))
          .claim(TokenClaims.AMR, List.of("pwd"))
          .claim(TokenClaims.AUTH_TIME, iat.getEpochSecond());
    } else {
      c.subject(TokenClaims.SERVICE_SUBJECT_PREFIX + "reco").claim(TokenClaims.CLIENT_ID, "reco");
    }
    JWTClaimsSet claims = c.build();
    try {
      return switch (d) {
        case "alg-none" -> Base64URL.encode(("{\"alg\":\"none\",\"typ\":\"" + JwtSettings.ACCESS_TOKEN_TYPE + "\"}")
            .getBytes(StandardCharsets.UTF_8)) + "." + Base64URL.encode(claims.toString()
            .getBytes(StandardCharsets.UTF_8)) + ".";
        case "hs256-confusion" -> {
          // the classic attack: HMAC "signed" with the published public key as the secret
          byte[] secret = current.toPublicJWK().toJSONString().getBytes(StandardCharsets.UTF_8);
          SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(current.getKeyID())
              .type(new JOSEObjectType(JwtSettings.ACCESS_TOKEN_TYPE)).build(), claims);
          jwt.sign(new MACSigner(secret));
          yield jwt.serialize();
        }
        case "unknown-kid" -> sign(newKey("lab-unpublished"), null, JwtSettings.ACCESS_TOKEN_TYPE, claims);
        case "embedded-jwk" -> {
          ECKey own = newKey("lab-attacker");
          yield sign(own, own.toPublicJWK(), JwtSettings.ACCESS_TOKEN_TYPE, claims);
        }
        case "wrong-typ" -> sign(current, null, "JWT", claims);
        case "bad-signature" -> flipSignature(sign(current, null, JwtSettings.ACCESS_TOKEN_TYPE, claims));
        default -> sign(current, null, JwtSettings.ACCESS_TOKEN_TYPE, claims);
      };
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sign(ECKey key, JWK embedded, String typ, JWTClaimsSet claims) throws JOSEException {
    JWSHeader.Builder h = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(new JOSEObjectType(typ));
    if (embedded != null) {
      h.jwk(embedded);
    }
    SignedJWT jwt = new SignedJWT(h.build(), claims);
    jwt.sign(new ECDSASigner(key));
    return jwt.serialize();
  }

  /** Changes one character in the middle of the signature (still valid base64url, wrong signature). */
  static String flipSignature(String jws) {
    int dot = jws.lastIndexOf('.');
    int i = dot + 10;
    char c = jws.charAt(i);
    return jws.substring(0, i) + (c == 'A' ? 'B' : 'A') + jws.substring(i + 1);
  }
}
