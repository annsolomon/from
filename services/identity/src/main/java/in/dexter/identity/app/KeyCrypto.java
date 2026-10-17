package in.dexter.identity.app;

import in.dexter.identity.app.SigningKeyStore.KeyRow;
import java.util.List;

/** Signing-key material (infra: Nimbus ES256 + KEK envelope); keeps JOSE types out of the use-cases. */
public interface KeyCrypto {

  record NewKey(String kid, String publicJwk, byte[] privateEnc, String kekId) {}

  NewKey generate();

  /** Signs {@code claims} as an {@code at+jwt} with the key of {@code row} (must be decryptable). */
  String sign(KeyRow row, TokenClaimsSpec claims);

  /** The public JWKS document of {@code published} keys. */
  String jwks(List<KeyRow> published);
}
