package in.dexter.core.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Base of every expected (client-visible) failure. dexter-web maps it to an RFC 9457 ProblemDetail
 * with {@code code}, the code's HTTP status, and {@link #detail()} as the detail text. The detail is
 * shown to clients: never put secrets, SQL or stack information into it.
 */
public class DexterException extends RuntimeException {
  private final ErrorCode errorCode;
  private final List<FieldError> fieldErrors;
  private final Map<String, Object> properties;

  public DexterException(ErrorCode errorCode, String detail) {
    this(errorCode, detail, List.of(), Map.of(), null);
  }

  public DexterException(ErrorCode errorCode, String detail, Throwable cause) {
    this(errorCode, detail, List.of(), Map.of(), cause);
  }

  public DexterException(ErrorCode errorCode, String detail, List<FieldError> fieldErrors,
      Map<String, ?> properties, Throwable cause) {
    super(detail == null ? errorCode.title() : detail, cause);
    this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    this.fieldErrors = List.copyOf(fieldErrors == null ? List.of() : fieldErrors);
    this.properties = Map.copyOf(properties == null ? Map.of() : new LinkedHashMap<>(properties));
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  public String detail() {
    return getMessage();
  }

  public List<FieldError> fieldErrors() {
    return fieldErrors;
  }

  /** Extra ProblemDetail members (for example {@code currentVersion}). Values must be JSON-safe. */
  public Map<String, Object> properties() {
    return properties;
  }
}
