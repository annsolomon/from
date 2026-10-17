package in.dexter.gateway.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Test issuer for the gateway: one ES256 key published by the identity WireMock backend. */
public final class Tokens {
  public static final String ISSUER = "https://identity.gateway.test";
  static final ECKey KEY;

  static {
    try {
      KEY = new ECKeyGenerator(Curve.P_256).keyID("gw-test-1").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256)
          .generate();
    } catch (com.nimbusds.jose.JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private Tokens() {}

  public static String jwks() {
    return new JWKSet(KEY.toPublicJWK()).toString();
  }

  public static String user(UUID account) {
    return sign(account.toString(), List.of("gateway", "identity", "ledger"), 600, List.of("USER"), "user");
  }

  public static String user() {
    return user(UUID.randomUUID());
  }

  public static String expired() {
    return sign(UUID.randomUUID().toString(), List.of("gateway"), -120, List.of("USER"), "user");
  }

  public static String wrongAudience() {
    return sign(UUID.randomUUID().toString(), List.of("ledger"), 600, List.of("USER"), "user");
  }

  public static String service() {
    return sign("svc:ledger", List.of("gateway"), 300, null, "service");
  }

  static String sign(String sub, List<String> aud, long ttlSeconds, List<String> roles, String scope) {
    Instant now = Instant.now();
    Instant iat = ttlSeconds < 0 ? now.minusSeconds(600) : now;
    JWTClaimsSet.Builder c = new JWTClaimsSet.Builder()
        .issuer(ISSUER).subject(sub).audience(aud).issueTime(Date.from(iat))
        .expirationTime(Date.from(ttlSeconds < 0 ? now.plusSeconds(ttlSeconds) : now.plusSeconds(ttlSeconds)))
        .jwtID(UUID.randomUUID().toString()).claim("client_id", "dexter-app").claim("scope", scope);
    if (!sub.startsWith("svc:")) {
      c.claim("sid", UUID.randomUUID().toString()).claim("roles", roles).claim("amr", List.of("pwd"))
          .claim("auth_time", now.getEpochSecond());
    }
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KEY.getKeyID())
          .type(new JOSEObjectType("at+jwt")).build(), c.build());
      jwt.sign(new ECDSASigner(KEY));
      return jwt.serialize();
    } catch (com.nimbusds.jose.JOSEException e) {
      throw new IllegalStateException(e);
    }
  }
}
