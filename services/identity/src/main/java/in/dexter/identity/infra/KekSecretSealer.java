package in.dexter.identity.infra;

import in.dexter.identity.app.SecretSealer;
import java.nio.charset.StandardCharsets;

/** {@link SecretSealer} with the KEK ring (AES-256-GCM, caller-chosen aad binds the ciphertext to its row). */
public final class KekSecretSealer implements SecretSealer {
  private final KekRing kek;

  public KekSecretSealer(KekRing kek) {
    this.kek = kek;
  }

  @Override
  public Sealed seal(String aad, byte[] plaintext) {
    return new Sealed(kek.activeId(), kek.wrap(aad.getBytes(StandardCharsets.UTF_8), plaintext));
  }

  @Override
  public byte[] open(String kekId, String aad, byte[] sealed) {
    return kek.unwrap(kekId, aad.getBytes(StandardCharsets.UTF_8), sealed);
  }
}
