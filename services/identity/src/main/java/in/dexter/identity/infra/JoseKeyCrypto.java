package in.dexter.identity.infra;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import in.dexter.identity.app.KeyCrypto;
import in.dexter.identity.app.SigningKeyStore.KeyRow;
import in.dexter.identity.app.TokenClaimsSpec;
import in.dexter.security.jwt.JwtSettings;
import in.dexter.security.jwt.TokenClaims;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ES256 signing keys with Nimbus (A-18: {@code alg=ES256}, {@code typ=at+jwt}, {@code kid}; no embedded keys).
 * Private JWKs are stored AES-GCM-wrapped by the KEK (aad {@code signing-key:<kid>}); decrypted keys are cached
 * per kid in memory only.
 */
public final class JoseKeyCrypto implements KeyCrypto {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final JOSEObjectType AT_JWT = new JOSEObjectType(JwtSettings.ACCESS_TOKEN_TYPE);

  private final KekRing kek;
  private final Map<String, ECDSASigner> signers = new ConcurrentHashMap<>();

  public JoseKeyCrypto(KekRing kek) {
    this.kek = kek;
  }

  @Override
  public NewKey generate() {
    byte[] id = new byte[16];
    RANDOM.nextBytes(id);
    String kid = Base64.getUrlEncoder().withoutPadding().encodeToString(id);
    try {
      ECKey key = new ECKeyGenerator(Curve.P_256).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256)
          .generate();
      byte[] privateEnc = kek.wrap(aad(kid), key.toJSONString().getBytes(StandardCharsets.UTF_8));
      return new NewKey(kid, key.toPublicJWK().toJSONString(), privateEnc, kek.activeId());
    } catch (JOSEException e) {
      throw new IllegalStateException("EC key generation failed", e);
    }
  }

  @Override
  public String sign(KeyRow row, TokenClaimsSpec c) {
    ECDSASigner signer = signers.computeIfAbsent(row.kid(), kid -> signer(row));
    JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256).type(AT_JWT).keyID(row.kid()).build();
    JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
        .issuer(c.issuer())
        .subject(c.subject())
        .audience(c.audiences())
        .issueTime(Date.from(c.issuedAt()))
        .expirationTime(Date.from(c.issuedAt().plus(c.ttl())))
        .jwtID(c.tokenId())
        .claim(TokenClaims.CLIENT_ID, c.clientId());
    put(b, TokenClaims.SESSION_ID, c.sessionId());
    put(b, TokenClaims.SCOPE, c.scope());
    put(b, TokenClaims.ROLES, c.roles());
    put(b, TokenClaims.AMR, c.amr());
    put(b, TokenClaims.AUTH_TIME, c.authTime() == null ? null : c.authTime().getEpochSecond());
    put(b, TokenClaims.MFA_ENROLLED, c.mfaEnrolled());
    put(b, TokenClaims.TZ, c.tz());
    put(b, TokenClaims.TIER, c.tier());
    put(b, TokenClaims.PERMISSIONS, c.permissions());
    SignedJWT jwt = new SignedJWT(header, b.build());
    try {
      jwt.sign(signer);
    } catch (JOSEException e) {
      throw new IllegalStateException("signing failed", e);
    }
    return jwt.serialize();
  }

  @Override
  public String jwks(List<KeyRow> published) {
    List<JWK> keys = new ArrayList<>();
    for (KeyRow r : published) {
      try {
        keys.add(ECKey.parse(r.publicJwk()).toPublicJWK());
      } catch (ParseException e) {
        throw new IllegalStateException("stored public JWK " + r.kid() + " is invalid", e);
      }
    }
    return new JWKSet(keys).toString(true);
  }

  private ECDSASigner signer(KeyRow row) {
    byte[] json = kek.unwrap(row.kekId(), aad(row.kid()), row.privateEnc());
    try {
      return new ECDSASigner(ECKey.parse(new String(json, StandardCharsets.UTF_8)));
    } catch (ParseException | JOSEException e) {
      throw new IllegalStateException("stored private JWK " + row.kid() + " is invalid", e);
    }
  }

  private static void put(JWTClaimsSet.Builder b, String name, Object value) {
    if (value != null) {
      b.claim(name, value);
    }
  }

  private static byte[] aad(String kid) {
    return ("signing-key:" + kid).getBytes(StandardCharsets.UTF_8);
  }
}
