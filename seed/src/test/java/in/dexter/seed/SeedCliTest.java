package in.dexter.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeedCliTest {
  final ByteArrayOutputStream out = new ByteArrayOutputStream();
  final ByteArrayOutputStream err = new ByteArrayOutputStream();

  int run(Map<String, String> env, String... args) {
    return new SeedCli(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true,
        StandardCharsets.UTF_8), env).run(args);
  }

  @Test
  void generateThenChecksumAgree(@TempDir Path tmp) {
    String dir = tmp.resolve("ds").toString();
    assertThat(run(GeneratorTest.PW, "generate", "--size", "small", "--seed", "42", "--out", dir)).isZero();
    String printed = out.toString(StandardCharsets.UTF_8);
    assertThat(printed).contains("identity.accounts").contains("checksum ");
    String checksum = printed.substring(printed.lastIndexOf("checksum ") + 9).trim();
    out.reset();
    assertThat(run(Map.of(), "checksum", "--dir", dir)).isZero();
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("files verified: " + Tables.ALL.size())
        .contains("checksum " + checksum);
  }

  @Test
  void badInputExitsNonZeroWithAReason(@TempDir Path tmp) {
    assertThat(run(Map.of())).isEqualTo(2);
    assertThat(run(Map.of(), "frobnicate")).isEqualTo(2);
    assertThat(run(Map.of(), "generate", "--size")).isEqualTo(2);
    assertThat(run(Map.of(), "generate", "--size", "huge")).isEqualTo(1);
    assertThat(run(Map.of(), "generate", "--size", "small", "--out", tmp.toString())).isEqualTo(1);
    assertThat(err.toString(StandardCharsets.UTF_8)).contains("DEMO_PASSWORD_ASHA is not set");
    assertThat(run(Map.of(), "load", "--dir", tmp.resolve("none").toString())).isEqualTo(1);
  }

  @Test
  void sizesAreExact() {
    assertThat(Size.MEDIUM).isEqualTo(new Size("medium", 10_000, 1_200_000, 30_000));
    assertThat(Size.LARGE.accounts()).isEqualTo(100_000);
    assertThat(Size.LARGE.expenses()).isEqualTo(5_000_000);
    assertThat(Size.parse("custom:10,2000,20").bulkExpenses()).isEqualTo(1_000);
    assertThatThrownBy(() -> Size.parse("custom:2,999,5")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Size.parse("custom:3,1000,12")).hasMessageContaining("too many cards");
  }
}
