package in.dexter.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.events.topics.TopicRegistry;
import in.dexter.events.topics.TopicSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopicsAndTypesTest {
  record NoteCreated(String title) {}

  @Test
  void names() {
    assertThat(Topics.main("ledger", "expense")).isEqualTo("dexter.ledger.expense.v1");
    assertThat(Topics.retry("dexter.ledger.expense.v1", "reco-projection", "retry-1m"))
        .isEqualTo("dexter.ledger.expense.v1.reco-projection.retry-1m");
    assertThat(Topics.dlq("dexter.ledger.expense.v1", "reco-projection"))
        .isEqualTo("dexter.ledger.expense.v1.reco-projection.dlq");
    assertThatThrownBy(() -> Topics.main("Ledger", "x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Topics.retry("t", "c", "later")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void eventTypes() {
    EventType<NoteCreated> t = EventType.of("demo", "note", "created", 1, NoteCreated.class);
    assertThat(t.type()).isEqualTo("demo.note.created");
    assertThat(t.topic()).isEqualTo("dexter.demo.note.v1");
    assertThat(t.service()).isEqualTo("demo");
    assertThatThrownBy(() -> new EventType<>("demo.note", 1, "t", NoteCreated.class))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void registryDeclaresMainRetryAndDlq() {
    TopicRegistry r = new TopicRegistry(3, (short) 1)
        .main("demo", "note")
        .consumer(Topics.main("demo", "note"), "stats", List.of("retry-1m", "retry-10m"));
    assertThat(r.specs()).extracting(TopicSpec::name).containsExactly(
        "dexter.demo.note.v1",
        "dexter.demo.note.v1.stats.retry-1m",
        "dexter.demo.note.v1.stats.retry-10m",
        "dexter.demo.note.v1.stats.dlq");
    assertThat(r.specs().getFirst().retention()).isEqualTo(TopicRegistry.RETENTION);
    // same declaration twice is fine; conflicting settings are not
    r.main("demo", "note");
    assertThatThrownBy(() -> r.add(new TopicSpec("dexter.demo.note.v1", 12, (short) 1, TopicRegistry.RETENTION, false)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
