package in.dexter.portfolio.app;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Where statement files live (the S3-compatible object store, ADR-0003/ADR-0017). */
public interface StatementFiles {
  /** A presigned upload the client performs itself: {@code method url} with {@code headers}. */
  record Upload(String method, String url, Map<String, String> headers, Instant expiresAt) {}

  /** The object is not there (never uploaded, deleted, or expired). */
  final class Missing extends RuntimeException {
    public Missing(String key) {
      super("no object " + key);
    }
  }

  /** The object store could not be reached or answered an error. */
  final class Unavailable extends RuntimeException {
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** A presigned PUT on the public endpoint (the browser uploads to it). */
  Upload presignPut(String key, Duration ttl);

  /** A presigned GET on the internal endpoint (the ai-parser downloads from it). */
  URI presignInternalGet(String key, Duration ttl);

  /** The object's bytes, at most {@code maxBytes + 1} of them (more means too large); {@link Missing} when absent. */
  byte[] read(String key, long maxBytes);

  void put(String key, byte[] body, String contentType);

  /** Deletes the object; absent objects are fine. */
  void delete(String key);
}
