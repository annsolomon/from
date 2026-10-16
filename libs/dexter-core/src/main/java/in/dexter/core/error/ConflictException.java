package in.dexter.core.error;

/** 409 CONFLICT (or a service code with status 409). */
public class ConflictException extends DexterException {
  public ConflictException(String detail) {
    super(CoreErrors.CONFLICT, detail);
  }

  public ConflictException(ErrorCode code, String detail) {
    super(code, detail);
  }
}
