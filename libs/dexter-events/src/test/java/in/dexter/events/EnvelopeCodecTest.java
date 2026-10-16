package in.dexter.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EnvelopeCodecTest {
  record Data(String title, long amountPaise) {}

  final EnvelopeCodec codec = new EnvelopeCodec(JsonMapper.builder().build());

  EventEnvelope sample(UUID account) {
    return new EventEnvelope(UUID.fromString("0192f1e2-0000-7000-8000-000000000001"), "demo.note.created", 1, "demo",
        Instant.parse("2026-09-27T10:00:00.123Z"), account, UUID.fromString("0192f1e2-0000-7000-8000-0000000000aa"), 3,
        null, codec.mapper().valueToTree(new Data("lunch", 25_000)));
  }

  @Test
  void roundTrip() {
    EventEnvelope e = sample(UUID.fromString("0000000a-0000-7000-8000-000000000001"));
    String json = codec.encode(e);
    assertThat(json).contains("\"time\":\"2026-09-27T10:00:00.123Z\"").contains("\"aggregateVersion\":3");
    EventEnvelope back = codec.decode(json.getBytes(StandardCharsets.UTF_8));
    // Not assertThat(back).isEqualTo(e): Jackson trees are type-exact, and a long field becomes a
    // LongNode via valueToTree but an IntNode when the same JSON text is parsed. Compare the wire form.
    assertThat(codec.encode(back)).isEqualTo(json);
    assertThat(back.id()).isEqualTo(e.id());
    assertThat(back.time()).isEqualTo(e.time());
    assertThat(back.aggregateVersion()).isEqualTo(3);
    assertThat(codec.data(back, Data.class)).isEqualTo(new Data("lunch", 25_000));
    assertThat(back.messageKey()).isEqualTo("0000000a-0000-7000-8000-000000000001");
  }

  @Test
  void globalAggregateKeysByAggregateId() {
    EventEnvelope e = sample(null);
    assertThat(codec.decode(codec.encode(e)).accountId()).isNull();
    assertThat(e.messageKey()).isEqualTo("0192f1e2-0000-7000-8000-0000000000aa");
  }

  @Test
  void poisonIsRejected() {
    for (String bad : new String[] {
        "not json", "[]", "{}", "{\"id\":\"x\"}",
        codec.encode(sample(null)).replace("\"version\":1", "\"version\":\"1\""),
        codec.encode(sample(null)).replace("2026-09-27T10:00:00.123Z", "yesterday"),
        codec.encode(sample(null)).replace("\"data\":{", "\"data\":[{").replace("}}", "}]}")}) {
      assertThatThrownBy(() -> codec.decode(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
