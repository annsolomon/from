package in.dexter.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The pseudonym a completed deletion leaves behind (MASTER_PLAN P18 "audit keeps a pseudonymized record"):
 * HMAC-SHA-256 of the account id under a server key. Stable, so an operator holding a subject's id (from a legal
 * request) can find its record; not reversible without the key.
 */
public final class SubjectRef {
  private final byte[] key;

  public SubjectRef(byte[] key) {
    if (key.length < 32) {
      throw new IllegalArgumentException("the subject key needs >= 32 bytes");
    }
    this.key = key.clone();
  }

  public String of(UUID account) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(account.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
