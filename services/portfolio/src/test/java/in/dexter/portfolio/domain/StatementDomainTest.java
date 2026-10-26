package in.dexter.portfolio.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** P12 domain: the upload scan and the statement-line references and checks. */
class StatementDomainTest {
  static final UUID CARD = UUID.fromString("0192f1e2-0000-7000-8000-0000000000b1");
  static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

  static StatementLines.Line line(int no, String day, String desc, long paise, String dir, String kind) {
    return new StatementLines.Line(no, LocalDate.parse(day), desc, paise, dir, kind);
  }

  @Test
  void scanAcceptsPdfsWithinTheCapOnly() {
    byte[] pdf = "%PDF-1.7\nhello".getBytes(StandardCharsets.US_ASCII);
    StatementScan.Result ok = StatementScan.scan(pdf, 100);
    assertThat(ok.ok()).isTrue();
    assertThat(ok.clean().sizeBytes()).isEqualTo(pdf.length);
    assertThat(ok.clean().sha256Hex()).hasSize(64).isEqualTo(StatementScan.scan(pdf.clone(), 100).clean().sha256Hex());
    assertThat(StatementScan.scan(new byte[0], 100).rejection()).isEqualTo(StatementScan.Rejection.EMPTY_FILE);
    assertThat(StatementScan.scan(null, 100).rejection()).isEqualTo(StatementScan.Rejection.EMPTY_FILE);
    assertThat(StatementScan.scan(pdf, pdf.length - 1).rejection()).isEqualTo(StatementScan.Rejection.FILE_TOO_LARGE);
    assertThat(StatementScan.scan("%PDF".getBytes(StandardCharsets.US_ASCII), 100).rejection())
        .isEqualTo(StatementScan.Rejection.NOT_A_PDF);
    assertThat(StatementScan.scan("PK\u0003\u0004 zip".getBytes(StandardCharsets.ISO_8859_1), 100).rejection())
        .isEqualTo(StatementScan.Rejection.NOT_A_PDF);
    assertThat(StatementScan.scan(" %PDF-1.4".getBytes(StandardCharsets.US_ASCII), 100).ok())
        .as("the magic must be the first bytes").isFalse();
  }

  @Test
  void referencesAreStablePerCardAndCountOccurrences() {
    List<StatementLines.Line> lines = List.of(
        line(1, "2026-09-07", "SWIGGY*BANGALORE", 45_000, "debit", "purchase"),
        line(2, "2026-09-07", "swiggy*bangalore ", 45_000, "debit", "purchase"),
        line(3, "2026-09-07", "SWIGGY*BANGALORE", 45_000, "credit", "refund"));
    List<String> refs = StatementLines.refs(CARD, lines);
    assertThat(refs).allMatch(r -> r.matches("stmt:[0-9a-f]{40}")).doesNotHaveDuplicates();
    assertThat(StatementLines.refs(CARD, lines)).as("deterministic").isEqualTo(refs);
    assertThat(StatementLines.refs(UUID.randomUUID(), lines)).as("per card").doesNotContainAnyElementsOf(refs);
    // the same two identical purchases in another file (other line numbers) get the same references
    List<StatementLines.Line> shifted = new ArrayList<>();
    shifted.add(line(1, "2026-09-01", "OTHER", 100, "debit", "purchase"));
    shifted.add(line(2, "2026-09-07", "SWIGGY*BANGALORE", 45_000, "debit", "purchase"));
    shifted.add(line(3, "2026-09-07", "SWIGGY*BANGALORE", 45_000, "debit", "purchase"));
    List<String> again = StatementLines.refs(CARD, shifted);
    assertThat(again.subList(1, 3)).isEqualTo(refs.subList(0, 2));
  }

  @Test
  void problemsWithParsedLines() {
    List<StatementLines.Line> good = List.of(line(1, "2026-10-26", "A", 1, "debit", "purchase"),
        line(2, "2026-10-01", "B", 1, "credit", "payment"));
    assertThat(StatementLines.problem(good, TODAY)).isNull();
    assertThat(StatementLines.problem(List.of(), TODAY)).isEqualTo("NO_TRANSACTIONS");
    assertThat(StatementLines.problem(List.of(line(1, "2026-10-27", "A", 1, "debit", "purchase")), TODAY))
        .isEqualTo("FUTURE_DATED_LINES");
    assertThat(StatementLines.problem(List.of(line(2, "2026-10-01", "A", 1, "debit", "purchase")), TODAY))
        .as("numbered from 1").isEqualTo("BAD_LINES");
    assertThat(StatementLines.problem(List.of(line(1, "2026-10-01", "A", 1, "credit", "purchase")), TODAY))
        .as("kind vs direction").isEqualTo("BAD_LINES");
    assertThat(StatementLines.problem(List.of(line(1, "2026-10-01", "A", 0, "debit", "purchase")), TODAY))
        .isEqualTo("BAD_LINES");
    assertThat(StatementLines.problem(List.of(line(1, "2026-10-01", "x".repeat(201), 1, "debit", "fee")), TODAY))
        .isEqualTo("BAD_LINES");
    assertThat(StatementLines.problem(List.of(line(1, "2026-10-01", "A", 1, "debit", "cashout")), TODAY))
        .isEqualTo("BAD_LINES");
    List<StatementLines.Line> many = new ArrayList<>();
    for (int i = 1; i <= StatementLines.MAX_LINES + 1; i++) {
      many.add(line(i, "2026-10-01", "A", 1, "debit", "purchase"));
    }
    assertThat(StatementLines.problem(many, TODAY)).isEqualTo("TOO_MANY_LINES");
  }

  @Test
  void linesPostAtTheStartOfTheirIstDay() {
    Instant t = StatementLines.postedAt(LocalDate.of(2026, 9, 7));
    assertThat(t).isEqualTo(Instant.parse("2026-09-06T18:30:00Z"));
    assertThat(BillingCycle.day(t)).isEqualTo(LocalDate.of(2026, 9, 7));
  }
}
