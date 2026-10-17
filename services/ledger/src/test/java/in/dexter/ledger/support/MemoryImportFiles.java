package in.dexter.ledger.support;

import in.dexter.ledger.app.ImportFiles;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link ImportFiles} for the ITs: {@link #put} plays the browser's presigned PUT. */
public final class MemoryImportFiles implements ImportFiles {
  private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

  public void put(String key, byte[] bytes) {
    objects.put(key, bytes.clone());
  }

  @Override
  public Upload presignPut(String key, Duration ttl) {
    return new Upload("PUT", "memory://" + key, Map.of("Content-Type", "text/csv"), Instant.now().plus(ttl));
  }

  @Override
  public InputStream open(String key) {
    byte[] b = objects.get(key);
    if (b == null) {
      throw new Missing(key);
    }
    return new ByteArrayInputStream(b);
  }
}
