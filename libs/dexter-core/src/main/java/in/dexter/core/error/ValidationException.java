package in.dexter.core.error;

import java.util.List;
import java.util.Map;

/** 400 VALIDATION_FAILED with field errors. */
public class ValidationException extends DexterException {
  public ValidationException(List<FieldError> errors) {
    super(CoreErrors.VALIDATION_FAILED, "One or more fields are invalid", errors, Map.of(), null);
  }

  public static ValidationException of(String field, String code, String message) {
    return new ValidationException(List.of(new FieldError(field, code, message)));
  }
}
