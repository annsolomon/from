package in.dexter.identity.infra;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Key-encryption keys (MASTER_PLAN 7.4: env in dev, Vault Transit in P19, ADR-0026). New data keys and signing keys
 * are wrapped with {@link #activeId()}; older ids stay for unwrapping until re-wrapped (rotation).
 *
 * <p>Two kinds of KEK: <b>local</b> ({@code k1}, ...: 32 bytes from configuration, AES-256-GCM in this process) and
 * <b>remote</b> ({@code transit:<key>}: the key never leaves Vault; wrap and unwrap are Transit calls). A ring may hold
 * both, so data wrapped under a local KEK still opens after the active KEK moved to Transit.
 */
public final class KekRing {
  /** Prefix of a KEK held by Vault Transit. */
  public static final String TRANSIT_PREFIX = "transit:";

  /** A KEK held outside the process: wraps and unwraps, never reveals the key. */
  public interface RemoteKek {
    /** {@code transit:<key name>}; stored next to every value it wrapped. */
    String id();

    byte[] wrap(byte[] aad, byte[] plaintext);

    byte[] unwrap(byte[] aad, byte[] sealed);
  }

  private final String activeId;
  private final Map<String, byte[]> keys;
  private final @Nullable RemoteKek remote;

  public KekRing(String activeId, Map<String, byte[]> keys) {
    this(activeId, keys, null);
  }

  public KekRing(String activeId, Map<String, byte[]> keys, @Nullable RemoteKek remote) {
    this.activeId = Objects.requireNonNull(activeId, "activeId");
    this.remote = remote;
    Map<String, byte[]> copy = new HashMap<>();
    keys.forEach((id, k) -> {
      if (id.startsWith(TRANSIT_PREFIX)) {
        throw new IllegalArgumentException("local KEK id " + id + " uses the reserved prefix " + TRANSIT_PREFIX);
      }
      if (k == null || k.length != 32) {
        throw new IllegalArgumentException("KEK " + id + " must be 32 bytes (base64 of 32 random bytes)");
      }
      copy.put(id, k.clone());
    });
    if (!copy.containsKey(activeId) && (remote == null || !remote.id().equals(activeId))) {
      throw new IllegalArgumentException("active KEK id " + activeId + " is not configured");
    }
    this.keys = Map.copyOf(copy);
  }

  /** From {@code id → base64} configuration. */
  public static KekRing fromBase64(String activeId, Map<String, String> base64Keys) {
    return fromBase64(activeId, base64Keys, null);
  }

  public static KekRing fromBase64(String activeId, Map<String, String> base64Keys, @Nullable RemoteKek remote) {
    Map<String, byte[]> m = new HashMap<>();
    base64Keys.forEach((id, b64) -> {
      if (b64 != null && !b64.isBlank()) {
        m.put(id, Base64.getDecoder().decode(b64.strip()));
      }
    });
    return new KekRing(activeId, m, remote);
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
    if (remote != null && remote.id().equals(activeId)) {
      return remote.wrap(aad, plaintext);
    }
    return Aead.seal(keys.get(activeId), aad, plaintext);
  }

  byte[] unwrap(String kekId, byte[] aad, byte[] sealed) {
    if (kekId.startsWith(TRANSIT_PREFIX)) {
      if (remote == null || !remote.id().equals(kekId)) {
        throw new IllegalStateException("KEK " + kekId + " is in Vault Transit, which is not configured here");
      }
      return remote.unwrap(aad, sealed);
    }
    return Aead.open(key(kekId), aad, sealed);
  }
}
