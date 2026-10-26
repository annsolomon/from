package in.dexter.portfolio.app;

import in.dexter.portfolio.app.CardStore.CardRow;
import in.dexter.portfolio.app.StatementStore.ParsedRow;
import in.dexter.portfolio.app.StatementStore.UploadRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * API representation of a statement upload (P12). {@code upload} (the presigned PUT) only on create; {@code parsed}
 * (the parser's header) once parsed, on the detail and on confirm; {@code result} (what confirm did) once confirmed.
 */
public record UploadView(UUID id, UUID userCardId, String cardLast4, String fileName, Long sizeBytes, String status,
    String errorCode, String errorDetail, String sha256, Instant createdAt, Instant submittedAt, Instant parsedAt,
    Instant decidedAt, int version, StatementFiles.Upload upload, Parsed parsed, Result result) {

  /** The statement as the parser read it; {@code cardMatches} false = the PDF is for another card. */
  public record Parsed(String parserVersion, String format, String issuer, String last4, Boolean cardMatches,
      LocalDate periodStart, LocalDate periodEnd, LocalDate dueDate, Long totalDuePaise, Long minDuePaise,
      int redactions, int lineCount, long debitsPaise, long creditsPaise, int duplicateLines) {}

  /** What confirm did with the lines. */
  public record Result(int imported, int duplicates, int excluded) {}

  static UploadView of(UploadRow u, ParsedRow p, CardRow card, StatementFiles.Upload upload) {
    Parsed parsed = p == null ? null : new Parsed(p.parserVersion(), p.format(), p.issuer(), p.last4(),
        p.last4() == null || card == null ? null : p.last4().equals(card.last4()), p.periodStart(), p.periodEnd(),
        p.dueDate(), p.totalDuePaise(), p.minDuePaise(), p.redactions(), p.lineCount(), p.debitsPaise(),
        p.creditsPaise(), p.duplicateCount());
    Result result = u.importedCount() == null ? null
        : new Result(u.importedCount(), nz(u.duplicateCount()), nz(u.excludedCount()));
    return new UploadView(u.id(), u.userCardId(), card == null ? null : card.last4(), u.fileName(), u.sizeBytes(),
        u.status(), u.errorCode(), u.errorDetail(), StatementService.hex(u.sha256()), u.createdAt(), u.submittedAt(),
        u.parsedAt(), u.decidedAt(), u.version(), upload, parsed, result);
  }

  private static int nz(Integer v) {
    return v == null ? 0 : v;
  }
}
