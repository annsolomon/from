package in.dexter.identity.infra;

import in.dexter.identity.app.KekRewrapStore;
import in.dexter.identity.app.KekRewrapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Re-wraps with the KEK ring. The associated data is the one each writer used — {@code dek:<account>}
 * ({@link EnvelopeCipher}), {@code signing-key:<kid>} ({@link JoseKeyCrypto}), {@code admin-totp:<admin>}
 * (AdminAuthService via {@link KekSecretSealer}) — so a re-wrapped key opens exactly where it opened before.
 */
public final class KekRingRewrapper implements KekRewrapper {
  private final KekRing ring;

  public KekRingRewrapper(KekRing ring) {
    this.ring = ring;
  }

  @Override
  public String activeId() {
    return ring.activeId();
  }

  @Override
  public byte[] rewrap(KekRewrapStore.Wrapped row) {
    byte[] aad = aad(row.kind(), row.id());
    byte[] plain = ring.unwrap(row.kekId(), aad, row.sealed());
    try {
      return ring.wrap(aad, plain);
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  static byte[] aad(String kind, String id) {
    String prefix = switch (kind) {
      case "account_key" -> "dek:";
      case "signing_key" -> "signing-key:";
      case "admin_totp" -> "admin-totp:";
      default -> throw new IllegalArgumentException("unknown wrapped-key kind " + kind);
    };
    return (prefix + id).getBytes(StandardCharsets.UTF_8);
  }
}
