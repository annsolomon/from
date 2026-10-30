package in.dexter.planning.app;

import in.dexter.core.error.FieldError;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/** Shared field checks (errors are collected, MASTER_PLAN 9). */
final class Inputs {
  static final Pattern CATEGORY = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){0,3}$");
  static final long MAX_AMOUNT = 100_000_000_000L;

  private Inputs() {}

  static String text(String field, String v, int max, boolean required, List<FieldError> e) {
    if (v == null || v.isBlank()) {
      if (required) {
        e.add(new FieldError(field, "REQUIRED", "Required"));
      }
      return null;
    }
    String s = v.strip();
    if (s.length() > max) {
      e.add(new FieldError(field, "TOO_LONG", "At most " + max + " characters"));
    }
    return s;
  }

  /** A positive money amount in paise. */
  static long amount(String field, Long v, List<FieldError> e) {
    if (v == null) {
      e.add(new FieldError(field, "REQUIRED", "Positive paise"));
      return 0;
    }
    if (v < 1 || v > MAX_AMOUNT) {
      e.add(new FieldError(field, "OUT_OF_RANGE", "1 to " + MAX_AMOUNT + " paise"));
    }
    return v;
  }

  static LocalDate date(String field, String v, boolean required, List<FieldError> e) {
    if (v == null || v.isBlank()) {
      if (required) {
        e.add(new FieldError(field, "REQUIRED", "A date (YYYY-MM-DD)"));
      }
      return null;
    }
    try {
      return LocalDate.parse(v.strip());
    } catch (DateTimeParseException ex) {
      e.add(new FieldError(field, "INVALID", "A date (YYYY-MM-DD)"));
      return null;
    }
  }

  /** An ltree category path, or null for "all spend". */
  static String category(String field, String v, List<FieldError> e) {
    if (v == null || v.isBlank()) {
      return null;
    }
    String s = v.strip();
    if (!CATEGORY.matcher(s).matches()) {
      e.add(new FieldError(field, "INVALID", "A category path like food.dining"));
      return null;
    }
    return s;
  }

  static int count(String field, Integer v, int min, int max, boolean required, List<FieldError> e) {
    if (v == null) {
      if (required) {
        e.add(new FieldError(field, "REQUIRED", min + "-" + max));
      }
      return min;
    }
    if (v < min || v > max) {
      e.add(new FieldError(field, "OUT_OF_RANGE", min + "-" + max));
      return min;
    }
    return v;
  }
}
