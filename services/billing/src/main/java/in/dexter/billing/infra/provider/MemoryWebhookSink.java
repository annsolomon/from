package in.dexter.billing.infra.provider;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Keeps the mock provider's webhooks instead of posting them ({@code dexter.billing.mock-provider.delivery=memory},
 * the shape of identity's in-memory mail transport). Tests take a delivery from here and send it themselves - twice,
 * out of order, concurrently or never - which is what the replay and ordering tests need control of.
 */
public final class MemoryWebhookSink implements MockPaymentProvider.Sink {

  /** One delivery exactly as the provider would have sent it. */
  public record Delivery(String signature, byte[] body) {
    public String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    public boolean is(String type, String reference) {
      String t = text();
      return t.contains("\"type\":\"" + type + "\"") && t.contains("\"reference\":\"" + reference + "\"");
    }
  }

  private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();

  @Override
  public void deliver(String signatureHeader, byte[] body) {
    deliveries.add(new Delivery(signatureHeader, body.clone()));
  }

  public List<Delivery> deliveries() {
    return List.copyOf(deliveries);
  }

  /** The first delivery matching {@code p}, waiting up to {@code within} for it. */
  public Delivery await(Predicate<Delivery> p, Duration within) {
    long end = System.nanoTime() + within.toNanos();
    while (true) {
      for (Delivery d : deliveries) {
        if (p.test(d)) {
          return d;
        }
      }
      if (System.nanoTime() > end) {
        throw new AssertionError("no matching webhook delivery within " + within + "; have " + deliveries.size());
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }
}
