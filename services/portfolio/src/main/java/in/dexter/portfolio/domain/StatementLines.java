package in.dexter.portfolio.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Parsed statement lines (MASTER_PLAN P12): the reference a line gets as a card transaction, and the checks a line
 * from the parser must pass before it is staged.
 *
 * <p>The reference is {@code stmt:} + 40 hex characters of SHA-256 over (card, posting day, amount, direction,
 * normalised description, occurrence). The occurrence counts identical earlier lines of the same statement, so two
 * genuine identical purchases on one day stay two transactions, while the same statement confirmed twice, or a
 * second file with the same lines, imports each line once (the client_ref is unique per account).
 */
public final class StatementLines {
  public static final int MAX_LINES = 2000;
  public static final Set<String> KINDS = Set.of("purchase", "refund", "payment", "fee", "interest", "cashback", "emi");
  public static final Set<String> DEBIT_KINDS = Set.of("purchase", "fee", "interest", "emi");

  /** A line as the parser reported it. */
  public record Line(int lineNo, LocalDate postedOn, String description, long amountPaise, String direction,
      String kind) {}

  private StatementLines() {}

  /** References for the lines, in order (occurrences counted within this list). */
  public static List<String> refs(UUID cardId, List<Line> lines) {
    Map<String, Integer> seen = new HashMap<>();
    List<String> out = new ArrayList<>(lines.size());
    for (Line l : lines) {
      String identity = cardId + "|" + l.postedOn() + "|" + l.amountPaise() + "|" + l.direction() + "|"
          + normalise(l.description());
      int occurrence = seen.merge(identity, 1, Integer::sum);
      byte[] h = StatementScan.sha256((identity + "|" + occurrence).getBytes(StandardCharsets.UTF_8));
      out.add("stmt:" + HexFormat.of().formatHex(h, 0, 20));
    }
    return out;
  }

  static String normalise(String description) {
    return description == null ? "" : String.join(" ", description.toUpperCase(Locale.ROOT).strip().split("\\s+"));
  }

  /**
   * Why the parser's lines cannot be staged (an upload error code), or null when they can: 1-2000 lines numbered
   * 1..n, positive amounts within the card-transaction bound, known kinds matching the direction, a description of
   * 1-200 characters, and no posting day after {@code today} (IST).
   */
  public static String problem(List<Line> lines, LocalDate today) {
    if (lines == null || lines.isEmpty()) {
      return "NO_TRANSACTIONS";
    }
    if (lines.size() > MAX_LINES) {
      return "TOO_MANY_LINES";
    }
    for (int i = 0; i < lines.size(); i++) {
      Line l = lines.get(i);
      if (l.lineNo() != i + 1 || l.postedOn() == null || l.description() == null || l.description().isBlank()
          || l.description().length() > 200 || l.amountPaise() < 1 || l.amountPaise() > 1_000_000_000_000L
          || !KINDS.contains(l.kind())
          || !(DEBIT_KINDS.contains(l.kind()) ? "debit" : "credit").equals(l.direction())) {
        return "BAD_LINES";
      }
      if (l.postedOn().isAfter(today)) {
        return "FUTURE_DATED_LINES";
      }
    }
    return null;
  }

  /** When a statement line posts: the start of its day in IST (the billing-cycle day of the card transaction). */
  public static Instant postedAt(LocalDate day) {
    return day.atStartOfDay(BillingCycle.ZONE).toInstant();
  }
}
