package in.dexter.portfolio.support;

import in.dexter.portfolio.app.StatementFiles;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Statement files in memory (the ITs have no object store; api-smoke covers SeaweedFS with identities). Uploads go
 * straight into {@link #objects} with {@link #upload}; {@link #down} simulates an unreachable store.
 */
public final class MemoryStatementFiles implements StatementFiles {
  public final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  public final AtomicInteger reads = new AtomicInteger();
  public final AtomicBoolean down = new AtomicBoolean();

  /** What the browser would PUT to the presigned URL. */
  public void upload(String key, byte[] body) {
    objects.put(key, body.clone());
  }

  @Override
  public Upload presignPut(String key, Duration ttl) {
    check();
    return new Upload("PUT", "http://objectstore.test/dexter-statements/" + key + "?X-Amz-Signature=test",
        Map.of("Content-Type", "application/pdf"), Instant.now().plus(ttl));
  }

  @Override
  public URI presignInternalGet(String key, Duration ttl) {
    check();
    return URI.create("http://127.0.0.1:8333/dexter-statements/" + key + "?X-Amz-Signature=test");
  }

  @Override
  public byte[] read(String key, long maxBytes) {
    check();
    reads.incrementAndGet();
    byte[] b = objects.get(key);
    if (b == null) {
      throw new Missing(key);
    }
    return Arrays.copyOf(b, (int) Math.min(b.length, maxBytes + 1));
  }

  @Override
  public void put(String key, byte[] body, String contentType) {
    check();
    objects.put(key, body.clone());
  }

  @Override
  public void delete(String key) {
    check();
    objects.remove(key);
  }

  private void check() {
    if (down.get()) {
      throw new Unavailable("store down (test)", null);
    }
  }
}
