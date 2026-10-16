package in.dexter.core.error;

import java.util.Objects;

/** One field-level validation problem, rendered into ProblemDetail {@code errors[]}. */
public record FieldError(String field, String code, String message) {
  public FieldError {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(code, "code");
    message = message == null ? "" : message;
  }
}
