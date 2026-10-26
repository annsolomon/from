package in.dexter.portfolio.infra.objectstore;

import in.dexter.objectstore.S3Objects;
import in.dexter.portfolio.app.StatementFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Statement files on the S3 API (ADR-0003, ADR-0017) through {@link S3Objects}: the browser PUTs to the public
 * endpoint, the service reads/writes/deletes on the internal one, the ai-parser GETs with a presigned internal URL.
 * Transport failures become {@link StatementFiles.Unavailable}.
 */
public final class S3StatementFiles implements StatementFiles {
  private final S3Objects objects;
  private final Clock clock;

  public S3StatementFiles(S3Objects objects, Clock clock) {
    this.objects = objects;
    this.clock = clock;
  }

  @Override
  public Upload presignPut(String key, Duration ttl) {
    try {
      URI url = objects.presign("PUT", key, ttl);
      return new Upload("PUT", url.toString(), Map.of("Content-Type", "application/pdf"), clock.instant().plus(ttl));
    } catch (RuntimeException e) {
      throw new Unavailable("presign failed", e);
    }
  }

  @Override
  public URI presignInternalGet(String key, Duration ttl) {
    try {
      return objects.presignInternal("GET", key, ttl);
    } catch (RuntimeException e) {
      throw new Unavailable("presign failed", e);
    }
  }

  @Override
  public byte[] read(String key, long maxBytes) {
    try (InputStream in = objects.open(key)) {
      return in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
    } catch (S3Objects.Missing e) {
      throw new Missing(key);
    } catch (IOException | UncheckedIOException | IllegalStateException e) {
      throw new Unavailable("read " + key + " failed", e);
    }
  }

  @Override
  public void put(String key, byte[] body, String contentType) {
    try {
      objects.put(key, body, contentType);
    } catch (UncheckedIOException | IllegalStateException e) {
      throw new Unavailable("put " + key + " failed", e);
    }
  }

  @Override
  public void delete(String key) {
    try {
      objects.delete(key);
    } catch (UncheckedIOException | IllegalStateException e) {
      throw new Unavailable("delete " + key + " failed", e);
    }
  }
}
