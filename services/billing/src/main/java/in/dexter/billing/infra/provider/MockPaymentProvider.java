package in.dexter.billing.infra.provider;

import in.dexter.billing.app.PaymentProvider;
import in.dexter.billing.domain.PaymentStatus;
import in.dexter.billing.domain.WebhookSignature;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The mock payment provider (MASTER_PLAN P16): behaves like a real one in every way Mode B and the webhook path depend
 * on, and decides the outcome from the payment-method token, as a provider's test cards do:
 *
 * <table>
 *   <tr><td>{@code pm_mock_success}</td><td>succeeds at once; webhooks {@code payment.created} then
 *       {@code payment.succeeded}</td></tr>
 *   <tr><td>{@code pm_mock_decline} / {@code pm_mock_insufficient}</td><td>fails at once ({@code card_declined} /
 *       {@code insufficient_funds}); {@code payment.created} then {@code payment.failed}</td></tr>
 *   <tr><td>{@code pm_mock_delayed}</td><td>pending at checkout; {@code payment.succeeded} arrives after the
 *       configured delay - the asynchronous case (UPI collect, a 3-D Secure challenge)</td></tr>
 *   <tr><td>{@code pm_mock_delayed_decline}</td><td>pending, then {@code payment.failed}</td></tr>
 *   <tr><td>anything else</td><td>fails at once with {@code payment_method_invalid}</td></tr>
 * </table>
 *
 * Charges are idempotent on the caller's account-scoped key, {@link #lookup} answers by Dexter's reference, and every webhook is
 * HMAC-signed ({@link WebhookSignature}) and delivered later, on another thread, through a {@link Sink} - over HTTP
 * to billing's own {@code /v1/webhooks/mock} in a running stack, captured in tests so they can deliver it twice, out
 * of order, or not at all. State lives in this JVM: it is a dev and test double, one instance only (BUILD_README §7).
 */
public final class MockPaymentProvider implements PaymentProvider, AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(MockPaymentProvider.class);
  public static final String NAME = "mock";

  /** Where signed webhook deliveries go. */
  @FunctionalInterface
  public interface Sink {
    void deliver(String signatureHeader, byte[] body);
  }

  public record Settings(byte[] secret, Duration webhookDelay, Duration delayedOutcomeAfter, boolean webhooks) {}

  private static final class Charge {
    final UUID reference;
    final String paymentId;
    final String subscriptionId;
    final long amountPaise;
    volatile PaymentStatus status;
    volatile String failureCode;

    Charge(UUID reference, String paymentId, String subscriptionId, long amountPaise, PaymentStatus status,
        String failureCode) {
      this.reference = reference;
      this.paymentId = paymentId;
      this.subscriptionId = subscriptionId;
      this.amountPaise = amountPaise;
      this.status = status;
      this.failureCode = failureCode;
    }
  }

  private final Settings settings;
  private final Sink sink;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final Map<String, Charge> byKey = new ConcurrentHashMap<>();
  private final Map<UUID, Charge> byReference = new ConcurrentHashMap<>();
  private final AtomicInteger charges = new AtomicInteger();
  private final AtomicBoolean unavailable = new AtomicBoolean();
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "billing-mock-provider");
    t.setDaemon(true);
    return t;
  });

  public MockPaymentProvider(Settings settings, Sink sink, ObjectMapper mapper, Clock clock) {
    this.settings = settings;
    this.sink = sink;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Override
  public String name() {
    return NAME;
  }

  /** Charges actually created (a retry with the same key is not a new charge) - what the Mode B tests count. */
  public int chargesCreated() {
    return charges.get();
  }

  /** Simulates an outage: every call throws {@link PaymentProvider.Unavailable} until switched off. */
  public void unavailable(boolean down) {
    unavailable.set(down);
  }

  @Override
  public ChargeResult charge(ChargeRequest r) {
    if (unavailable.get()) {
      throw new Unavailable("mock provider is down", null);
    }
    Charge c = byKey.computeIfAbsent(r.providerIdempotencyKey(), k -> create(r));
    return result(c);
  }

  @Override
  public Optional<ChargeResult> lookup(UUID reference) {
    if (unavailable.get()) {
      throw new Unavailable("mock provider is down", null);
    }
    return Optional.ofNullable(byReference.get(reference)).map(MockPaymentProvider::result);
  }

  @Override
  public WebhookSignature.Check verify(String signatureHeader, byte[] body, Instant now) {
    return WebhookSignature.verify(settings.secret(), signatureHeader, body, now, WebhookSignature.TOLERANCE);
  }

  @Override
  public WebhookEvent parse(byte[] body) {
    JsonNode n;
    try {
      n = mapper.readTree(body);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("not JSON");
    }
    if (n == null || !n.isObject() || !text(n, "id").startsWith("evt_") || !n.has("data")) {
      throw new IllegalArgumentException("not a mock provider event");
    }
    String type = text(n, "type");
    PaymentStatus status = switch (type) {
      case "payment.created" -> PaymentStatus.PENDING;
      case "payment.succeeded" -> PaymentStatus.SUCCEEDED;
      case "payment.failed" -> PaymentStatus.FAILED;
      default -> throw new IllegalArgumentException("unknown event type " + type);
    };
    JsonNode d = n.get("data");
    UUID reference;
    try {
      reference = d.hasNonNull("reference") ? UUID.fromString(d.get("reference").asString()) : null;
    } catch (IllegalArgumentException e) {
      reference = null;
    }
    return new WebhookEvent(text(n, "id"), type, reference, nullable(d, "paymentId"), nullable(d, "subscriptionId"),
        status, nullable(d, "failureCode"));
  }

  // ------------------------------------------------------------------------------------------------ internals

  private Charge create(ChargeRequest r) {
    String token = r.paymentMethod() == null ? "" : r.paymentMethod().toLowerCase(Locale.ROOT);
    String hex = r.reference().toString().replace("-", "");
    String paymentId = "pay_mock_" + hex;
    String subId = "sub_mock_" + hex;
    PaymentStatus finalStatus;
    String finalFailure = null;
    boolean delayed = false;
    switch (token) {
      case "pm_mock_success" -> finalStatus = PaymentStatus.SUCCEEDED;
      case "pm_mock_decline" -> {
        finalStatus = PaymentStatus.FAILED;
        finalFailure = "card_declined";
      }
      case "pm_mock_insufficient" -> {
        finalStatus = PaymentStatus.FAILED;
        finalFailure = "insufficient_funds";
      }
      case "pm_mock_delayed" -> {
        finalStatus = PaymentStatus.SUCCEEDED;
        delayed = true;
      }
      case "pm_mock_delayed_decline" -> {
        finalStatus = PaymentStatus.FAILED;
        finalFailure = "card_declined";
        delayed = true;
      }
      default -> {
        finalStatus = PaymentStatus.FAILED;
        finalFailure = "payment_method_invalid";
      }
    }
    Charge c = new Charge(r.reference(), paymentId, subId, r.amountPaise(),
        delayed ? PaymentStatus.PENDING : finalStatus, delayed ? null : finalFailure);
    byReference.put(r.reference(), c);
    charges.incrementAndGet();
    String outcomeType = finalStatus == PaymentStatus.SUCCEEDED ? "payment.succeeded" : "payment.failed";
    String failure = finalFailure;
    schedule(settings.webhookDelay(), () -> send(c, "payment.created", PaymentStatus.PENDING, null));
    Duration decideAfter = delayed ? settings.delayedOutcomeAfter() : settings.webhookDelay().multipliedBy(2);
    schedule(decideAfter, () -> {
      c.status = finalStatus;
      c.failureCode = failure;
      send(c, outcomeType, finalStatus, failure);
    });
    return c;
  }

  private void schedule(Duration after, Runnable r) {
    try {
      timer.schedule(() -> {
        try {
          r.run();
        } catch (RuntimeException e) {
          log.warn("MOCK_PROVIDER_WEBHOOK_FAILED error={}", e.toString());
        }
      }, Math.max(0, after.toMillis()), TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.RejectedExecutionException e) {
      log.debug("mock provider stopped; webhook not scheduled");
    }
  }

  private void send(Charge c, String type, PaymentStatus status, String failureCode) {
    if (!settings.webhooks()) {
      return;
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("paymentId", c.paymentId);
    data.put("subscriptionId", c.subscriptionId);
    data.put("reference", c.reference.toString());
    data.put("amountPaise", c.amountPaise);
    data.put("currency", "INR");
    data.put("status", status.db());
    data.put("failureCode", failureCode);
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("id", "evt_" + UUID.randomUUID().toString().replace("-", ""));
    event.put("type", type);
    event.put("created", clock.instant().getEpochSecond());
    event.put("data", data);
    byte[] body = mapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
    sink.deliver(WebhookSignature.header(settings.secret(), clock.instant().getEpochSecond(), body), body);
  }

  private static ChargeResult result(Charge c) {
    return new ChargeResult(c.paymentId, c.subscriptionId, c.status, c.failureCode);
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? "" : v.asString();
  }

  private static String nullable(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }

  @Override
  public void close() {
    timer.shutdownNow();
  }
}
