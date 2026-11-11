package in.dexter.audit.app;

import java.net.URI;
import java.time.Duration;

/** The object store bucket of the audit service: export parts and archives, chain anchors. */
public interface ExportStore {
  void put(String key, byte[] bytes, String contentType);

  /** The object's bytes; {@link Missing} when absent. */
  byte[] get(String key);

  /** A presigned GET on the public endpoint. */
  URI presign(String key, Duration ttl);

  final class Missing extends RuntimeException {
    public Missing(String key) {
      super("no object " + key);
    }
  }
}
