package in.dexter.db.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KeysetCursorCodecTest {
  static final byte[] K1 = "k1-0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  static final byte[] K2 = "k2-0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  static final String F = KeysetCursorCodec.filterHash(Map.of("category", "food"));
  static final List<String> KEYS = List.of("2026-09-01T10:00:00Z", "0192f0c1-7d3e-7abc-8def-0123456789ab");

  static KeysetCursorCodec codec(String active, Map<String, byte[]> keys) {
    return new KeysetCursorCodec(new CursorKeyRing(active, keys));
  }

  static final KeysetCursorCodec C1 = codec("c1", Map.of("c1", K1));

  static void assertCode(Runnable r, CoreErrors code) {
    assertThatThrownBy(r::run).isInstanceOf(DexterException.class)
        .satisfies(e -> assertThat(((DexterException) e).errorCode()).isEqualTo(code));
  }

  @Test
  void roundTrip() {
    String cur = C1.encode("occurred_desc", KEYS, F);
    CursorPosition p = C1.decode(cur, "occurred_desc", F);
    assertThat(p.keys()).isEqualTo(KEYS);
    assertThat(p.kid()).isEqualTo("c1");
    assertThat(cur).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
  }

  @Test
  void tamperedPayloadOrSignatureIsInvalid() {
    String cur = C1.encode("occurred_desc", KEYS, F);
    int dot = cur.indexOf('.');
    String payloadJson = new String(Base64.getUrlDecoder().decode(cur.substring(0, dot)), StandardCharsets.UTF_8);
    String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
        payloadJson.replace("2026-09-01", "2030-01-01").getBytes(StandardCharsets.UTF_8));
    assertCode(() -> C1.decode(forged + cur.substring(dot), "occurred_desc", F), CoreErrors.CURSOR_INVALID);
    char last = cur.charAt(cur.length() - 1);
    String badSig = cur.substring(0, cur.length() - 1) + (last == 'A' ? 'B' : 'A');
    assertCode(() -> C1.decode(badSig, "occurred_desc", F), CoreErrors.CURSOR_INVALID);
  }

  /** Every single-character change is rejected, including the base64 padding bits of the last char. */
  @Test
  void everySingleCharacterTamperIsInvalid() {
    String cur = C1.encode("occurred_desc", KEYS, F);
    for (int i = 0; i < cur.length(); i++) {
      if (cur.charAt(i) == '.') {
        continue;
      }
      String t = cur.substring(0, i) + (cur.charAt(i) == 'A' ? 'B' : 'A') + cur.substring(i + 1);
      assertCode(() -> C1.decode(t, "occurred_desc", F), CoreErrors.CURSOR_INVALID);
    }
  }

  @Test
  void garbageIsInvalid() {
    for (String g : new String[] {"", "abc", "a.b.c", ".", "x.", "!!!.???", "e30.AAAA", "a".repeat(3000) + ".b"}) {
      assertCode(() -> C1.decode(g, "occurred_desc", F), CoreErrors.CURSOR_INVALID);
    }
  }

  @Test
  void filterMismatchAndWrongSort() {
    String cur = C1.encode("occurred_desc", KEYS, F);
    assertCode(() -> C1.decode(cur, "occurred_desc", KeysetCursorCodec.filterHash(Map.of("category", "travel"))),
        CoreErrors.CURSOR_FILTER_MISMATCH);
    assertCode(() -> C1.decode(cur, "amount_desc", F), CoreErrors.CURSOR_INVALID);
  }

  @Test
  void kidRotation() {
    String old = C1.encode("s", KEYS, F);
    KeysetCursorCodec both = codec("c2", Map.of("c1", K1, "c2", K2));
    assertThat(both.decode(old, "s", F).kid()).isEqualTo("c1");
    String fresh = both.encode("s", KEYS, F);
    assertThat(both.decode(fresh, "s", F).kid()).isEqualTo("c2");
    KeysetCursorCodec retired = codec("c2", Map.of("c2", K2));
    assertCode(() -> retired.decode(old, "s", F), CoreErrors.CURSOR_INVALID);
    assertThat(retired.decode(fresh, "s", F).keys()).isEqualTo(KEYS);
  }

  @Test
  void filterHashIsOrderIndependentAndIgnoresNulls() {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("from", "2026-01-01");
    a.put("category", "food");
    a.put("cardId", null);
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("category", "food");
    b.put("from", "2026-01-01");
    assertThat(KeysetCursorCodec.filterHash(a)).isEqualTo(KeysetCursorCodec.filterHash(b)).hasSize(16);
    assertThat(KeysetCursorCodec.filterHash(Map.of("a", "b=c"))).isNotEqualTo(KeysetCursorCodec.filterHash(Map.of("a=b", "c")));
  }

  @Test
  void keyRingValidation() {
    assertThatThrownBy(() -> new CursorKeyRing("c1", Map.of("c1", new byte[8]))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CursorKeyRing("c9", Map.of("c1", K1))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CursorKeyRing("C1", Map.of("C1", K1))).isInstanceOf(IllegalArgumentException.class);
  }
}
