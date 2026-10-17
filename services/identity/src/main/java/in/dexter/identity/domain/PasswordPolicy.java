package in.dexter.identity.domain;

import in.dexter.core.error.FieldError;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * NIST SP 800-63B style: length 10..128, no composition rules, not on the breached list, not the email.
 * Length counts code points so emoji and non-Latin passwords are measured fairly.
 */
public final class PasswordPolicy {
  public static final int MIN = 10;
  public static final int MAX = 128;

  /** Known-breached passwords (bundled list, MASTER_PLAN 7.1). */
  @FunctionalInterface
  public interface BreachedList {
    boolean contains(String password);
  }

  private PasswordPolicy() {}

  public static List<FieldError> check(String field, String password, String email, BreachedList breached) {
    List<FieldError> errors = new ArrayList<>();
    if (password == null || password.isEmpty()) {
      errors.add(new FieldError(field, "REQUIRED", "Password is required"));
      return errors;
    }
    int len = password.codePointCount(0, password.length());
    if (len < MIN) {
      errors.add(new FieldError(field, "TOO_SHORT", "Use at least " + MIN + " characters"));
    } else if (len > MAX) {
      errors.add(new FieldError(field, "TOO_LONG", "Use at most " + MAX + " characters"));
    } else if (breached.contains(password)) {
      errors.add(new FieldError(field, "BREACHED", "This password appears in known data breaches; choose another"));
    } else if (email != null && password.toLowerCase(Locale.ROOT).contains(email.toLowerCase(Locale.ROOT))) {
      errors.add(new FieldError(field, "CONTAINS_EMAIL", "The password must not contain your email address"));
    }
    return errors;
  }
}
