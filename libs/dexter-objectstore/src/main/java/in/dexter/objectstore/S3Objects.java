package in.dexter.objectstore;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One bucket on an S3-compatible store (ADR-0003; SeaweedFS in dev). Requests are presigned with {@link SigV4}
 * (path style: {@code endpoint/bucket/key}), so the same code serves browser uploads ({@link #presign}) and
 * server-side reads/writes ({@link #put}, {@link #open}). The bucket is created on first use (PUT bucket answers 200,
 * or 409 when it exists).
 */
public final class S3Objects {
  private static final Logger log = LoggerFactory.getLogger(S3Objects.class);

  /** The object does not exist. */
  public static final class Missing extends RuntimeException {
    public Missing(String key) {
      super("no object " + key);
    }
  }

  private final SigV4 signer;
  private final String endpoint;
  private final String publicEndpoint;
  private final String bucket;
  private final Clock clock;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final AtomicBoolean bucketReady = new AtomicBoolean();

  /**
   * @param endpoint       what this service reaches, e.g. {@code http://127.0.0.1:8333}
   * @param publicEndpoint what a browser reaches (presigned uploads); blank = {@code endpoint}
   */
  public S3Objects(SigV4 signer, String endpoint, String publicEndpoint, String bucket, Clock clock) {
    this.signer = signer;
    this.endpoint = endpoint;
    this.publicEndpoint = publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
    this.bucket = bucket;
    this.clock = clock;
  }

  public String bucket() {
    return bucket;
  }

  /** A presigned URL on the public endpoint (browser upload or download). */
  public URI presign(String method, String key, Duration ttl) {
    ensureBucket();
    return signer.presign(method, publicEndpoint, "/" + bucket + "/" + key, ttl, clock.instant());
  }

  /** Stores the bytes under {@code key} (overwrites). */
  public void put(String key, byte[] body, String contentType) {
    ensureBucket();
    URI url = signer.presign("PUT", endpoint, "/" + bucket + "/" + key, Duration.ofMinutes(5), clock.instant());
    try {
      HttpResponse<String> r = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30))
          .header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
          .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() / 100 != 2) {
        throw new IllegalStateException("object store PUT " + key + " answered " + r.statusCode());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Opens the object for reading; {@link Missing} when it does not exist. The caller closes the stream. */
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
