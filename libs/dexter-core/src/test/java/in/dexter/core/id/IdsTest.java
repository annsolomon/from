package in.dexter.core.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.core.error.DexterException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {
  @Test
  void v7AndMonotonic() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 10_000; i++) {
      ids.add(Ids.newId());
    }
    assertThat(ids).allMatch(Ids::isV7);
    List<UUID> sorted = new ArrayList<>(ids);
    sorted.sort((a, b) -> a.toString().compareTo(b.toString()));
    assertThat(sorted).containsExactlyElementsOf(ids);
    assertThat(Duration.between(Ids.timestampOf(ids.get(0)), Instant.now()).abs()).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  void strictParse() {
    UUID id = Ids.newId();
    assertThat(Ids.parse(id.toString(), "id")).isEqualTo(id);
    assertThatThrownBy(() -> Ids.parse("1-2-3-4-5", "id")).isInstanceOf(DexterException.class)
        .hasMessage("id must be a UUID");
    assertThatThrownBy(() -> Ids.parse(null, "id")).isInstanceOf(DexterException.class);
  }
}
