package in.dexter.events;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** Fail-fast rules that need no database. The database behaviour is proven by the dexter-test ITs. */
class GuardsTest {
  record Data(String title) {}

  final JdbcClient jdbc = Mockito.mock(JdbcClient.class);

  @Test
  void outboxWriterNeedsATransaction() {
    OutboxWriter w = new OutboxWriter(jdbc, new EnvelopeCodec(JsonMapper.builder().build()), "demo", "demo",
        Clock.systemUTC(), () -> null);
    EventType<Data> t = EventType.of("demo", "note", "created", 1, Data.class);
    assertThatThrownBy(() -> w.append(t, UUID.randomUUID(), UUID.randomUUID(), 1, new Data("x")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("active transaction");
    Mockito.verifyNoInteractions(jdbc);
  }

  @Test
  void inboxGuardNeedsATransaction() {
    InboxGuard g = new InboxGuard(jdbc, "demo");
    assertThatThrownBy(() -> g.firstDelivery("stats", UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> g.firstDelivery("Bad Name", UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void projectionSqlMustCarryTheVersionGuard() {
    assertThatThrownBy(() -> ProjectionVersionGuard.apply(jdbc, "UPDATE demo.p SET n = 1 WHERE id = :id", Map.of(), 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ProjectionVersionGuard.apply(jdbc,
        "UPDATE demo.p SET projected_version = :version WHERE projected_version < :version", Map.of("version", 1), 2))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
