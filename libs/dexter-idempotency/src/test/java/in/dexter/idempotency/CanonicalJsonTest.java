package in.dexter.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CanonicalJsonTest {
  static final JsonMapper MAPPER = JsonMapper.builder().build();

  record Body(String title, long amountPaise, List<String> tags) {}

  @Test
  void keyOrderAndWhitespaceDoNotMatter() {
    String a = CanonicalJson.of(MAPPER, "{ \"b\": 1, \"a\": {\"y\": [1, 2], \"x\": \"s\"} }");
    String b = CanonicalJson.of(MAPPER, "{\"a\":{\"x\":\"s\",\"y\":[1,2]},\"b\":1}");
    assertThat(a).isEqualTo(b).isEqualTo("{\"a\":{\"x\":\"s\",\"y\":[1,2]},\"b\":1}");
  }

  @Test
  void recordsCanonicaliseLikeTheirJson() {
    String fromRecord = CanonicalJson.of(MAPPER, new Body("lunch", 25_000, List.of("food")));
    String fromText = CanonicalJson.of(MAPPER, "{\"tags\":[\"food\"],\"amountPaise\":25000,\"title\":\"lunch\"}");
    assertThat(fromRecord).isEqualTo(fromText);
  }

  @Test
  void numbersAreNormalised() {
    assertThat(CanonicalJson.write(List.of(new BigDecimal("1.50"), new BigDecimal("0.00"), 7L, new BigDecimal("1E+2"))))
        .isEqualTo("[1.5,0,7,100]");
  }

  @Test
  void stringsAreEscaped() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("q", "a\"b\\c\nd\u0001");
    assertThat(CanonicalJson.write(m)).isEqualTo("{\"q\":\"a\\\"b\\\\c\\nd\\u0001\"}");
  }

  @Test
  void valueChangesChangeTheText() {
    assertThat(CanonicalJson.of(MAPPER, "{\"a\":1}")).isNotEqualTo(CanonicalJson.of(MAPPER, "{\"a\":2}"));
    assertThat(CanonicalJson.of(MAPPER, null)).isEqualTo("null");
  }

  @Test
  void rejectsNonJsonValues() {
    assertThatThrownBy(() -> CanonicalJson.write(new Object())).isInstanceOf(IllegalArgumentException.class);
  }
}
