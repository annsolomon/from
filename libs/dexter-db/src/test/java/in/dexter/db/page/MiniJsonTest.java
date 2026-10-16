package in.dexter.db.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MiniJsonTest {
  @Test
  void roundTripsWithEscapes() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("v", 1);
    m.put("s", "a\"b\\c\n\u0001");
    m.put("k", List.of("x", "y"));
    String json = MiniJson.write(m);
    assertThat(MiniJson.parse(json)).isEqualTo(Map.of("v", 1L, "s", "a\"b\\c\n\u0001", "k", List.of("x", "y")));
  }

  @Test
  void rejectsBadInput() {
    for (String bad : new String[] {"{", "{\"a\":1,\"a\":2}", "[1,]", "{\"a\":1} x", "\"\\q\"", "[[[[[[1]]]]]]", "-"}) {
      assertThatThrownBy(() -> MiniJson.parse(bad)).as(bad).isInstanceOf(RuntimeException.class);
    }
  }
}
