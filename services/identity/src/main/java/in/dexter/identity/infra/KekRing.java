package in.dexter.identity.infra;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Key-encryption keys (MASTER_PLAN 7.4: env in dev, Vault Transit in P19). New data keys and signing keys are
 * wrapped with {@link #activeId()}; older ids stay for unwrapping until re-wrapped (rotation).
 */
public final class KekRing {
  private final String activeId;
  private final Map<String, byte[]> keys;

  public KekRing(String activeId, Map<String, byte[]> keys) {
    this.activeId = Objects.requireNonNull(activeId, "activeId");
    Map<String, byte[]> copy = new HashMap<>();
    keys.forEach((id, k) -> {
      if (k == null || k.length != 32) {
        throw new IllegalArgumentException("KEK " + id + " must be 32 bytes (base64 of 32 random bytes)");
      }
      copy.put(id, k.clone());
    });
    if (!copy.containsKey(activeId)) {
      throw new IllegalArgumentException("active KEK id " + activeId + " is not configured");
    }
    this.keys = Map.copyOf(copy);
  }

  /** From {@code id → base64} configuration. */
  public static KekRing fromBase64(String activeId, Map<String, String> base64Keys) {
    Map<String, byte[]> m = new HashMap<>();
    base64Keys.forEach((id, b64) -> m.put(id, Base64.getDecoder().decode(b64.strip())));
    return new KekRing(activeId, m);
  }

  public String activeId() {
    return activeId;
  }

  byte[] key(String id) {
    byte[] k = keys.get(id);
    if (k == null) {
      throw new IllegalStateException("KEK " + id + " is not configured (needed to unwrap a stored key)");
    }
    return k;
  }

  byte[] wrap(byte[] aad, byte[] plaintext) {
    return Aead.seal(keys.get(activeId), aad, plaintext);
  }

  byte[] unwrap(String kekId, byte[] aad, byte[] sealed) {
    return Aead.open(key(kekId), aad, sealed);
  }
}
