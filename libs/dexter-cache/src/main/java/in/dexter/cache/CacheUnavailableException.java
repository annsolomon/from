package in.dexter.cache;

/** Redis did not take a generation bump or purge. Thrown only where the caller can retry (Path B, purge). */
public final class CacheUnavailableException extends RuntimeException {
  public CacheUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
