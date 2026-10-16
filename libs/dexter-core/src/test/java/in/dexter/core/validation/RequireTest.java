package in.dexter.core.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.ValidationException;
import org.junit.jupiter.api.Test;

class RequireTest {
  @Test
  void collectsAllErrors() {
    ValidationException e = catchThrowableOfType(ValidationException.class,
        () -> Require.check().notBlank(" ", "name").positive(0, "amountPaise").maxLength("ok", 5, "note").orThrow());
    assertThat(e.errorCode()).isEqualTo(CoreErrors.VALIDATION_FAILED);
    assertThat(e.fieldErrors()).extracting("field").containsExactly("name", "amountPaise");
  }
}
