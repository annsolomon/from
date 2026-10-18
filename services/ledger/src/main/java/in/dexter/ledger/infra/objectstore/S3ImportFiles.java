package in.dexter.ledger.infra.objectstore;

import in.dexter.ledger.app.ImportFiles;
import in.dexter.objectstore.S3Objects;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * CSV uploads on the S3 API (ADR-0003) through {@link S3Objects} (libs/dexter-objectstore): the browser PUTs to the
 * public endpoint with a presigned URL, the service GETs from the internal one.
 */
public final class S3ImportFiles implements ImportFiles {
  private final S3Objects objects;
  private final Clock clock;

  public S3ImportFiles(S3Objects objects, Clock clock) {
    this.objects = objects;
    this.clock = clock;
  }

  @Override
  public Upload presignPut(String key, Duration ttl) {
    Instant now = clock.instant();
    URI url = objects.presign("PUT", key, ttl);
    return new Upload("PUT", url.toString(), Map.of("Content-Type", "text/csv"), now.plus(ttl));
  }

  @Override
  public InputStream open(String key) {
    try {
      return objects.open(key);
    } catch (S3Objects.Missing e) {
      throw new Missing(key);
    }
  }
}
