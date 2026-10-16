package in.dexter.db.page;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed opaque keyset cursors (MASTER_PLAN 4.2):
 * <pre>
 * payload = base64url(JSON{v:1, s:sort, k:[keys...], f:filterHash, kid})
 * cursor  = payload + "." + base64url(HMAC-SHA256(key[kid], payload)[0..16])
 * </pre>
 * The signature is checked (constant time) before any payload field is trusted. Errors:
 * 400 {@code CURSOR_INVALID} (garbage, tampering, unknown kid, wrong sort) and
 * 400 {@code CURSOR_FILTER_MISMATCH} (valid cursor reused with different filters).
 */
public final class KeysetCursorCodec {
  static final int MAX_CURSOR_LENGTH = 2048;
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getUrlDecoder();
  private final CursorKeyRing ring;

  public KeysetCursorCodec(CursorKeyRing ring) {
    this.ring = ring;
  }

  public String encode(String sort, List<String> keys, String filterHash) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("v", 1);
    m.put("s", sort);
    m.put("k", new ArrayList<>(keys));
    m.put("f", filterHash);
    m.put("kid", ring.activeKid());
    String payload = B64.encodeToString(MiniJson.write(m).getBytes(StandardCharsets.UTF_8));
    return payload + "." + B64.encodeToString(sign(ring.active(), payload));
  }

  public CursorPosition decode(String cursor, String expectedSort, String expectedFilterHash) {
    if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_CURSOR_LENGTH) {
      throw invalid();
    }
    int dot = cursor.indexOf('.');
    if (dot <= 0 || dot != cursor.lastIndexOf('.') || dot == cursor.length() - 1) {
      throw invalid();
    }
    String payload = cursor.substring(0, dot);
    byte[] sig;
    Map<?, ?> m;
    try {
      String sigText = cursor.substring(dot + 1);
      sig = B64D.decode(sigText);
      byte[] payloadBytes = B64D.decode(payload);
      // Canonical spelling only: base64 padding bits can otherwise encode one cursor two ways.
      if (!B64.encodeToString(sig).equals(sigText) || !B64.encodeToString(payloadBytes).equals(payload)) {
        throw invalid();
      }
      Object parsed = MiniJson.parse(new String(payloadBytes, StandardCharsets.UTF_8));
      if (!(parsed instanceof Map<?, ?> map)) {
        throw invalid();
      }
      m = map;
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
    if (!(m.get("kid") instanceof String kid)) {
      throw invalid();
    }
    byte[] key = ring.key(kid);
    if (key == null || !MessageDigest.isEqual(sign(key, payload), sig)) {
      throw invalid();
    }
    if (!Long.valueOf(1).equals(m.get("v")) || !(m.get("s") instanceof String sort)
        || !(m.get("k") instanceof List<?> rawKeys) || !(m.get("f") instanceof String f)) {
      throw invalid();
    }
    if (!sort.equals(expectedSort)) {
      throw invalid();
    }
    if (!f.equals(expectedFilterHash)) {
      throw new DexterException(CoreErrors.CURSOR_FILTER_MISMATCH,
          "The cursor was issued for different filters; restart from the first page");
    }
    List<String> keys = new ArrayList<>();
    for (Object o : rawKeys) {
      if (!(o instanceof String str)) {
        throw invalid();
      }
      keys.add(str);
    }
    return new CursorPosition(sort, keys, kid);
  }

  /**
   * Stable hash of the list filters: sorted {@code name=value} pairs, nulls dropped, SHA-256, first
   * 12 bytes base64url. Values are rendered with {@code String.valueOf}.
   */
  public static String filterHash(Map<String, ?> filters) {
    TreeMap<String, String> sorted = new TreeMap<>();
    if (filters != null) {
      filters.forEach((k, v) -> {
        if (v != null) {
          sorted.put(k, String.valueOf(v));
        }
      });
    }
    StringBuilder sb = new StringBuilder();
    sorted.forEach((k, v) -> sb.append(k.length()).append(':').append(k).append('=')
        .append(v.length()).append(':').append(v).append('&'));
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return B64.encodeToString(Arrays.copyOf(h, 12));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] sign(byte[] key, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return Arrays.copyOf(mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII)), 16);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(e);
    }
  }

  private static DexterException invalid() {
    return new DexterException(CoreErrors.CURSOR_INVALID, "The cursor is invalid or has expired");
  }
}
