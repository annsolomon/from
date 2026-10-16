package in.dexter.core.error;

/** 401 UNAUTHENTICATED. */
public class UnauthenticatedException extends DexterException {
  public UnauthenticatedException(String detail) {
    super(CoreErrors.UNAUTHENTICATED, detail);
  }
}
