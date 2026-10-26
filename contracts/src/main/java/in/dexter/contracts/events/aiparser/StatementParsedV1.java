package in.dexter.contracts.events.aiparser;

import java.util.List;
import java.util.UUID;

/**
 * {@code ai-parser.statement.parsed} v1 (MASTER_PLAN P12) on {@code dexter.ai-parser.statement.v1} (key = account
 * id), produced by the Python ai-parser for each {@code portfolio.statement.submitted}. Card numbers are redacted
 * before parsing ({@code redactions} = how many). {@code status} {@code failed} carries {@code errorCode} and no lines.
 * Dates are ISO {@code YYYY-MM-DD}; amounts are paise. The event id is derived from the submitted event's id, so a
 * redelivered submission yields the same event (the inbox drops it).
 */
public record StatementParsedV1(UUID uploadId, UUID userCardId, String status, String errorCode, String errorDetail,
    String parserVersion, String format, String issuer, String last4, String periodStart, String periodEnd,
    String dueDate, Long totalDuePaise, Long minDuePaise, int redactions, List<Line> lines, String parsedAt) {
  public static final String TYPE = "ai-parser.statement.parsed";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.ai-parser.statement.v1";

  /** One transaction row of the statement. */
  public record Line(int lineNo, String postedOn, String description, long amountPaise, String direction,
      String kind) {}
}
