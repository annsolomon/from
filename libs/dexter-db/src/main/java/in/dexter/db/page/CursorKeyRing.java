package in.dexter.db.page;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * HMAC keys for cursor signing, by key id (MASTER_PLAN 4.2). New cursors are signed with
 * {@code activeKid}; any key still in the ring verifies. Rotation = add the new key, make it active,
 * and remove the old key after the longest cursor lifetime you want to honour.
 */
public final class CursorKeyRing {
  private static final Pattern KID = Pattern.compile("[a-z0-9]{1,8}");
  private final String activeKid;
  private final Map<String, byte[]> keys;

  public CursorKeyRing(String activeKid, Map<String, byte[]> keys) {
    Objects.requireNonNull(activeKid, "activeKid");
    Map<String, byte[]> copy = new LinkedHashMap<>();
    keys.forEach((kid, key) -> {
      if (!KID.matcher(kid).matches()) {
        throw new IllegalArgumentException("cursor kid must match [a-z0-9]{1,8}: " + kid);
      }
      if (key == null || key.length < 32) {
        throw new IllegalArgumentException("cursor key " + kid + " must be at least 32 bytes");
      }
      copy.put(kid, key.clone());
    });
    if (!copy.containsKey(activeKid)) {
      throw new IllegalArgumentException("active cursor kid not in ring: " + activeKid);
    }
    this.activeKid = activeKid;
    this.keys = Map.copyOf(copy);
  }

  /** Keys as base64 (standard or url-safe) strings, as they come from configuration. */
  public static CursorKeyRing fromBase64(String activeKid, Map<String, String> base64Keys) {
    Map<String, byte[]> raw = new LinkedHashMap<>();
    base64Keys.forEach((kid, b64) -> raw.put(kid, decode(b64)));
    return new CursorKeyRing(activeKid, raw);
  }

  private static byte[] decode(String b64) {
    String t = b64.trim();
    return (t.indexOf('-') >= 0 || t.indexOf('_') >= 0 ? Base64.getUrlDecoder() : Base64.getDecoder()).decode(t);
  }

  public String activeKid() {
    return activeKid;
  }

  byte[] active() {
    return keys.get(activeKid);
  }

  byte[] key(String kid) {
    return keys.get(kid);
  }
}
