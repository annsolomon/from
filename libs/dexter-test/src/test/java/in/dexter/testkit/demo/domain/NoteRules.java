package in.dexter.testkit.demo.domain;

import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure validation (domain: no Spring, no JDBC). */
public final class NoteRules {
  public static final Set<String> KINDS = Set.of("note", "memo");

  private NoteRules() {}

  public static void validate(String title, String kind, long amountPaise) {
    List<FieldError> errors = new ArrayList<>();
    if (title == null || title.isBlank() || title.length() > 200) {
      errors.add(new FieldError("title", "SIZE", "must be 1..200 characters"));
    }
    if (kind == null || !KINDS.contains(kind)) {
      errors.add(new FieldError("kind", "INVALID", "must be one of " + KINDS));
    }
    if (amountPaise < 0) {
      errors.add(new FieldError("amountPaise", "MIN", "must be >= 0"));
    }
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
  }
}
