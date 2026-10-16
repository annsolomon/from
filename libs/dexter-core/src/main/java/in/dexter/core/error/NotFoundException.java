package in.dexter.core.error;

/** 404 NOT_FOUND. The detail names the resource type only, never whether another tenant owns the id. */
public class NotFoundException extends DexterException {
  public NotFoundException(String resource) {
    super(CoreErrors.NOT_FOUND, resource + " not found");
  }
}
