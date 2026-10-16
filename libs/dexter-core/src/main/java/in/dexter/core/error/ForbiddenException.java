package in.dexter.core.error;

/** 403 FORBIDDEN. */
public class ForbiddenException extends DexterException {
  public ForbiddenException(String detail) {
    super(CoreErrors.FORBIDDEN, detail);
  }
}
