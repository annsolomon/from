package in.dexter.ledger.app;

import in.dexter.core.error.FieldError;
import in.dexter.ledger.domain.CategoryPaths;
import in.dexter.ledger.domain.PaymentMethod;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Field validation of a new expense, shared by {@code POST /v1/expenses} and {@code POST /v1/expenses:bulk} (field
 * names get the item prefix there: {@code items[3].amountPaise}). Category visibility and merchant resolution need
 * the database and happen later, inside the write transaction.
 */
final class ExpenseInput {
  static final int MAX_TAGS = 10;
  static final int MAX_TAG_LENGTH = 30;
  static final long MAX_AMOUNT = 100_000_000_000L;

  /** A syntactically valid expense. {@code category} null = let the resolver decide. */
  record Valid(Instant occurredAt, long amountPaise, String category, PaymentMethod method, UUID userCardId,
      String merchant, String note, List<String> tags, String clientRef) {}

  private ExpenseInput() {}

  /** Adds errors (field names prefixed) and returns null when there are any. */
  static Valid validate(NewExpense cmd, String prefix, List<FieldError> errors) {
    int before = errors.size();
    if (cmd == null) {
      errors.add(new FieldError(prefix.isEmpty() ? "body" : prefix.substring(0, prefix.length() - 1), "REQUIRED",
          "An expense object"));
      return null;
    }
    if (cmd.occurredAt() == null) {
      errors.add(new FieldError(prefix + "occurredAt", "REQUIRED", "When did it happen (ISO-8601 instant)"));
    }
    long amount = amount(prefix + "amountPaise", cmd.amountPaise(), true, errors);
    String category = cmd.categoryPath();
    if (category != null && !CategoryPaths.isValid(category)) {
      errors.add(new FieldError(prefix + "categoryPath", "INVALID", "Not a category path"));
    }
    PaymentMethod method = PaymentMethod.parse(cmd.paymentMethod());
    if (method == null) {
      errors.add(new FieldError(prefix + "paymentMethod", cmd.paymentMethod() == null ? "REQUIRED" : "INVALID",
          "One of card, upi, cash, netbanking, wallet, other"));
    } else if (cmd.userCardId() != null && method != PaymentMethod.CARD) {
      errors.add(new FieldError(prefix + "userCardId", "NOT_A_CARD_PAYMENT", "Only card payments carry a card"));
    }
    String merchant = text(prefix + "merchant", cmd.merchant(), 120, errors);
    String note = text(prefix + "note", cmd.note(), 500, errors);
    List<String> tags = tags(prefix + "tags", cmd.tags(), errors);
    String clientRef = text(prefix + "clientRef", cmd.clientRef(), 100, errors);
    if (errors.size() > before) {
      return null;
    }
    return new Valid(cmd.occurredAt().truncatedTo(ChronoUnit.MICROS), amount, category, method, cmd.userCardId(),
        merchant, note, tags, clientRef);
  }

  static long amount(String field, Long v, boolean required, List<FieldError> errors) {
    if (v == null) {
      if (required) {
        errors.add(new FieldError(field, "REQUIRED", "Amount in paise (integer)"));
      }
      return 0;
    }
    if (v < 1 || v > MAX_AMOUNT) {
      errors.add(new FieldError(field, "OUT_OF_RANGE", "Between 1 and " + MAX_AMOUNT + " paise"));
    }
    return v;
  }

  static String text(String field, String v, int max, List<FieldError> errors) {
    if (v == null) {
      return null;
    }
    String s = v.strip();
    if (s.isEmpty()) {
      return null;
    }
    if (s.codePointCount(0, s.length()) > max) {
      errors.add(new FieldError(field, "TOO_LONG", "At most " + max + " characters"));
    }
    return s;
  }

  static List<String> tags(String field, List<String> raw, List<FieldError> errors) {
    if (raw == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (String t : raw) {
      String s = t == null ? "" : t.strip().toLowerCase(Locale.ROOT);
      if (s.isEmpty() || s.length() > MAX_TAG_LENGTH) {
        errors.add(new FieldError(field, "INVALID", "Tags are 1-" + MAX_TAG_LENGTH + " characters"));
        return List.of();
      }
      if (!out.contains(s)) {
        out.add(s);
      }
    }
    if (out.size() > MAX_TAGS) {
      errors.add(new FieldError(field, "TOO_MANY", "At most " + MAX_TAGS + " tags"));
    }
    return out;
  }
}
