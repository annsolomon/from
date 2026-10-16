package in.dexter.core.validation;

import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects field errors, then throws one {@link ValidationException} (400 VALIDATION_FAILED).
 * Bean Validation covers request records; this covers rules that need code.
 */
public final class Require {
  private final List<FieldError> errors = new ArrayList<>();

  public static Require check() {
    return new Require();
  }

  public Require that(boolean ok, String field, String code, String message) {
    if (!ok) {
      errors.add(new FieldError(field, code, message));
    }
    return this;
  }

  public Require notBlank(String value, String field) {
    return that(value != null && !value.isBlank(), field, "REQUIRED", field + " is required");
  }

  public Require maxLength(String value, int max, String field) {
    return that(value == null || value.length() <= max, field, "TOO_LONG", field + " must be at most " + max + " characters");
  }

  public Require positive(long value, String field) {
    return that(value > 0, field, "MUST_BE_POSITIVE", field + " must be > 0");
  }

  public Require range(long value, long min, long max, String field) {
    return that(value >= min && value <= max, field, "OUT_OF_RANGE", field + " must be between " + min + " and " + max);
  }

  public List<FieldError> errors() {
    return List.copyOf(errors);
  }

  public void orThrow() {
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
  }
}
