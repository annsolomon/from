package in.dexter.billing.infra.provider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers the mock provider's webhooks over HTTP to {@code <base>/v1/webhooks/mock}, the way a real provider calls
 * the public endpoint: a POST with the signature header, retried a few times with backoff until it is acknowledged
 * with a 2xx. The base URL is resolved at delivery time (in a running service it is the service's own port, known
 * only once the web server has started).
 */
public final class HttpWebhookSink implements MockPaymentProvider.Sink {
  private static final Logger log = LoggerFactory.getLogger(HttpWebhookSink.class);
  static final String HEADER = "Webhook-Signature";
  private final Supplier<String> baseUrl;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final int attempts;

  public HttpWebhookSink(Supplier<String> baseUrl, int attempts) {
    this.baseUrl = baseUrl;
    this.attempts = Math.max(1, attempts);
  }

  @Override
  public void deliver(String signatureHeader, byte[] body) {
    URI uri = URI.create(baseUrl.get() + "/v1/webhooks/" + MockPaymentProvider.NAME);
    for (int i = 1; i <= attempts; i++) {
      try {
        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json").header(HEADER, signatureHeader)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.discarding());
        if (r.statusCode() / 100 == 2) {
          return;
        }
        log.warn("MOCK_WEBHOOK_REFUSED status={} attempt={}", r.statusCode(), i);
      } catch (java.io.IOException e) {
        log.warn("MOCK_WEBHOOK_UNDELIVERED attempt={} error={}", i, e.toString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      try {
        Thread.sleep(250L * i * i);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }
}
