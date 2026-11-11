package in.dexter.audit.support;

import in.dexter.audit.app.ExportStore;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The audit bucket in memory (tests): parts, archives and anchors; presigned links are fake URLs with the TTL. */
public final class MemoryExportStore implements ExportStore {
  public final Map<String, byte[]> objects = new ConcurrentHashMap<>();

  @Override
  public void put(String key, byte[] bytes, String contentType) {
    objects.put(key, bytes.clone());
  }

  @Override
  public byte[] get(String key) {
    byte[] b = objects.get(key);
    if (b == null) {
      throw new Missing(key);
    }
    return b.clone();
  }

  @Override
  public URI presign(String key, Duration ttl) {
    return URI.create("http://objects.test/dexter-audit/" + key + "?X-Amz-Expires=" + ttl.toSeconds());
  }
}
