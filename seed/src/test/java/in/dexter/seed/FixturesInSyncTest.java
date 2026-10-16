package in.dexter.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The trail's mock mode serves {@code test-ui/fixtures/seed/*.json}. They are generated (never hand-edited), so a
 * change to the generator or the reference data must regenerate them: {@code ./scripts/seed.sh fixtures}.
 */
class FixturesInSyncTest {
  static final Path COMMITTED = Path.of("..", "test-ui", "fixtures", "seed");

  @Test
  void committedFixturesMatchAFreshSmallSeed(@TempDir Path tmp) throws Exception {
    Path fresh = tmp.resolve("fx");
    new Generator(new Generator.Config(42, Size.SMALL, tmp.resolve("out"), fresh, GeneratorTest.PW)).run();
    List<String> names;
    try (Stream<Path> s = Files.list(fresh)) {
      names = s.map(p -> p.getFileName().toString()).sorted().toList();
    }
    try (Stream<Path> s = Files.list(COMMITTED)) {
      assertThat(s.map(p -> p.getFileName().toString()).sorted().toList())
          .as("files in test-ui/fixtures/seed").isEqualTo(names);
    }
    for (String n : names) {
      assertThat(Files.mismatch(fresh.resolve(n), COMMITTED.resolve(n)))
          .as(n + " is stale - run ./scripts/seed.sh fixtures and commit test-ui/fixtures/seed").isEqualTo(-1L);
    }
  }
}
