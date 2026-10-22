package in.dexter.portfolio.app;

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

  static LocalDate date(String field, String v, List<FieldError> e) {
    if (v == null || v.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(v.strip());
    } catch (DateTimeParseException ex) {
      e.add(new FieldError(field, "INVALID", "A date (YYYY-MM-DD)"));
      return null;
    }
  }

  static boolean category(String v) {
    return v == null || CATEGORY.matcher(v).matches();
  }
}
