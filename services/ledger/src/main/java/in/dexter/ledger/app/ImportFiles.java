package in.dexter.ledger.app;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Where uploaded CSV files live (the S3-compatible object store, ADR-0003). */
public interface ImportFiles {
  /** A presigned upload the client performs itself: {@code method url} with {@code headers}. */
  record Upload(String method, String url, Map<String, String> headers, Instant expiresAt) {}

  /** The object is not there (never uploaded, or expired). */
  final class Missing extends RuntimeException {
    public Missing(String key) {
      super("no object " + key);
    }
  }

  Upload presignPut(String key, Duration ttl);

  /** The object's bytes; {@link Missing} when it does not exist. The caller closes the stream. */
  InputStream open(String key);
}
