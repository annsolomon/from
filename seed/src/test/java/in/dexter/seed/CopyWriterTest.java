package in.dexter.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopyWriterTest {

  private static String enc(Object v) {
    StringBuilder sb = new StringBuilder();
    CopyWriter.encode(v, sb);
    return sb.toString();
  }

  @Test
  void encodesEveryTypeTheWayCopyTextReadsIt() {
    assertThat(enc(null)).isEqualTo("\\N");
    assertThat(enc("a\tb\nc\\d\re")).isEqualTo("a\\tb\\nc\\\\d\\re");
    assertThat(enc("\\N")).isEqualTo("\\\\N"); // a literal backslash-N is text, not NULL
    assertThat(enc(UUID.fromString("018bcfe5-6800-7cc1-beeb-8da1658eec67"))).isEqualTo(
        "018bcfe5-6800-7cc1-beeb-8da1658eec67");
    assertThat(enc(12_345_678_901L)).isEqualTo("12345678901");
    assertThat(enc(7)).isEqualTo("7");
    assertThat(enc((short) 3)).isEqualTo("3");
    assertThat(enc(true)).isEqualTo("t");
    assertThat(enc(false)).isEqualTo("f");
    assertThat(enc(Instant.parse("2026-09-30T18:29:59.123456Z"))).isEqualTo("2026-09-30T18:29:59.123Z");
    assertThat(enc(Instant.parse("2026-09-30T18:29:59Z"))).isEqualTo("2026-09-30T18:29:59.000Z");
    assertThat(enc(LocalDate.of(2025, 10, 20))).isEqualTo("2025-10-20");
    assertThat(enc(List.of("family", "a\"b", "c\\d"))).isEqualTo("{\"family\",\"a\\\\\"b\",\"c\\\\\\\\d\"}");
    assertThat(enc(List.of())).isEqualTo("{}");
    assertThat(enc(new CopyWriter.Raw("{\"k\": \"v\\n\"}"))).isEqualTo("{\"k\": \"v\\\\n\"}");
    assertThatThrownBy(() -> enc(1.5)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void writesRowsAndHashesTheBytes(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("issuers.tsv");
    CopyWriter w = new CopyWriter(Tables.ISSUERS, f);
    w.row(new UUID(1, 2), "MOCK_X", "X Bank (MOCK)", "bank");
    w.row(new UUID(3, 4), "MOCK_Y", null, "nbfc");
    String sha = w.finish();
    assertThat(w.rows()).isEqualTo(2);
    String text = Files.readString(f);
    assertThat(text).isEqualTo("00000000-0000-0001-0000-000000000002\tMOCK_X\tX Bank (MOCK)\tbank\n"
        + "00000000-0000-0003-0000-000000000004\tMOCK_Y\t\\N\tnbfc\n");
    assertThat(sha).isEqualTo(Generator.sha256Hex(text));
    assertThatThrownBy(() -> new CopyWriter(Tables.ISSUERS, dir.resolve("b.tsv")).row("too", "few"))
        .hasMessageContaining("2 values for 4 columns");
  }

  @Test
  void stagingDdlListsTheColumnsInCopyOrder() {
    assertThat(Tables.ISSUERS.stagingDdl())
        .isEqualTo("CREATE TABLE IF NOT EXISTS seed_stage.issuers (id uuid, code text, name text, type text)");
    assertThat(Tables.ALL).extracting(Table::file).doesNotHaveDuplicates();
  }
}
