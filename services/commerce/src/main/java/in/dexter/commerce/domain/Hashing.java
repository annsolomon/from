package in.dexter.commerce.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Visitor identity for uniques and frequency caps without storing addresses: SHA-256 with a server-side salt.
 * A signed-in visitor is their account id; an anonymous one is (ip, user agent).
 */
public final class Hashing {
  private Hashing() {}

  public static byte[] sha256(byte[] in) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(in);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static byte[] salted(String salt, @Nullable String value) {
    return sha256((salt + "\u0000" + (value == null ? "" : value)).getBytes(StandardCharsets.UTF_8));
  }

  /** The visitor hash: the account when known, otherwise the salted (ip, ua) pair. */
  public static byte[] visitor(String salt, @Nullable UUID accountId, byte[] ipHash, byte[] uaHash) {
    if (accountId != null) {
      return salted(salt, "acct:" + accountId);
    }
    HexFormat hex = HexFormat.of();
    return salted(salt, "anon:" + hex.formatHex(ipHash) + ":" + hex.formatHex(uaHash));
  }

  public static String hex(byte[] b) {
    return HexFormat.of().formatHex(b);
  }
}
