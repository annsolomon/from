package in.dexter.audit.infra;

import in.dexter.audit.app.ExportStore;
import in.dexter.objectstore.S3Objects;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;

/** The audit bucket on the S3-compatible store (SeaweedFS in dev). */
public final class S3ExportStore implements ExportStore {
  private final S3Objects s3;

  public S3ExportStore(S3Objects s3) {
    this.s3 = s3;
  }

  @Override
  public void put(String key, byte[] bytes, String contentType) {
    s3.put(key, bytes, contentType);
  }

  @Override
  public byte[] get(String key) {
    try (InputStream in = s3.open(key)) {
      return in.readAllBytes();
    } catch (S3Objects.Missing e) {
      throw new Missing(key);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public URI presign(String key, Duration ttl) {
    return s3.presign("GET", key, ttl);
  }
}
