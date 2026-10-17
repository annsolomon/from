package in.dexter.ledger.infra.objectstore;

import in.dexter.ledger.app.ImportFiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CSV uploads on the S3 API (ADR-0003) with presigned URLs ({@link SigV4}): the browser PUTs to {@code publicEndpoint}
 * (what it can reach), the service GETs from {@code endpoint}. The bucket is created on first use (PUT bucket is
 * idempotent: 200, or 409 when it already exists).
 */
public final class S3ImportFiles implements ImportFiles {
  private static final Logger log = LoggerFactory.getLogger(S3ImportFiles.class);

  private final SigV4 signer;
  private final String endpoint;
  private final String publicEndpoint;
  private final String bucket;
  private final Clock clock;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final AtomicBoolean bucketReady = new AtomicBoolean();

  public S3ImportFiles(SigV4 signer, String endpoint, String publicEndpoint, String bucket, Clock clock) {
    this.signer = signer;
    this.endpoint = endpoint;
    this.publicEndpoint = publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
    this.bucket = bucket;
    this.clock = clock;
  }

  @Override
  public Upload presignPut(String key, Duration ttl) {
    ensureBucket();
    Instant now = clock.instant();
    URI url = signer.presign("PUT", publicEndpoint, "/" + bucket + "/" + key, ttl, now);
    return new Upload("PUT", url.toString(), Map.of("Content-Type", "text/csv"), now.plus(ttl));
  }

  @Override
  public InputStream open(String key) {
    URI url = signer.presign("GET", endpoint, "/" + bucket + "/" + key, Duration.ofMinutes(5), clock.instant());
    try {
      HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30)).GET().build(),
          HttpResponse.BodyHandlers.ofInputStream());
      if (r.statusCode() == 404) {
        r.body().close();
        throw new Missing(key);
      }
      if (r.statusCode() != 200) {
        r.body().close();
        throw new IllegalStateException("object store GET " + key + " answered " + r.statusCode());
      }
      return r.body();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private void ensureBucket() {
    if (bucketReady.get()) {
      return;
    }
    URI url = signer.presign("PUT", endpoint, "/" + bucket, Duration.ofMinutes(1), clock.instant());
    try {
      HttpResponse<String> r = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5))
          .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() == 200 || r.statusCode() == 409) {
        bucketReady.set(true);
      } else {
        log.warn("OBJECT_STORE_BUCKET bucket={} status={}", bucket, r.statusCode());
      }
    } catch (IOException e) {
      log.warn("OBJECT_STORE_UNREACHABLE endpoint={} error={}", endpoint, e.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
