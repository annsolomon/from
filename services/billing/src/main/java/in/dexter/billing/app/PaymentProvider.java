package in.dexter.billing.app;

import in.dexter.billing.domain.PaymentStatus;
import in.dexter.billing.domain.WebhookSignature;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The payment provider adapter (MASTER_PLAN P16): Dexter does not move money, a provider does. P16 ships one
 * implementation, {@code MockPaymentProvider}; a real adapter (Razorpay, Stripe) implements the same five methods.
 *
 * <p>Contract every adapter must honour, because Mode B recovery depends on it:
 * <ul>
 *   <li>{@link #charge} is idempotent on {@link ChargeRequest#providerIdempotencyKey} (account + Idempotency-Key):
 *       a retry with the same key returns the original charge and never charges twice (real providers take the key
 *       as a header).</li>
 *   <li>{@link #lookup} answers by Dexter's own {@code reference} (the payment id), and is empty only when the
 *       provider never saw the charge - that is what lets the sweeper close an attempt that died before the call.</li>
 *   <li>Webhooks carry the reference back, so an event can be tied to the payment without a token.</li>
 * </ul>
 */
public interface PaymentProvider {

  /** The adapter's name: the {@code {provider}} of {@code POST /v1/webhooks/{provider}} and the rows' provider. */
  String name();

  record ChargeRequest(UUID idempotencyKey, UUID reference, UUID accountId, long amountPaise, String currency,
      String paymentMethod, String description) {
    /**
     * The key to send the provider. An Idempotency-Key is unique per Dexter <i>account</i> (MASTER_PLAN 5.1), but
     * Dexter is one merchant to the provider, so two accounts that happen to pick the same key must not share a
     * charge: the provider's key is the pair. (IdempotencyContract case 6 caught the bare key in P16 r1.)
     */
    public String providerIdempotencyKey() {
      return accountId + ":" + idempotencyKey;
    }
  }

  /** What the provider says about a charge now. {@code failureCode} is set exactly when the status is FAILED. */
  record ChargeResult(String providerPaymentId, String providerSubscriptionId, PaymentStatus status,
      String failureCode) {}

  /** One parsed webhook delivery. {@code reference} is null when the event names no Dexter payment. */
  record WebhookEvent(String eventId, String type, UUID reference, String providerPaymentId,
      String providerSubscriptionId, PaymentStatus status, String failureCode) {}

  /** The provider could not be reached or answered with an error; whether the charge happened is unknown. */
  final class Unavailable extends RuntimeException {
    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  ChargeResult charge(ChargeRequest request);

  Optional<ChargeResult> lookup(UUID reference);

  WebhookSignature.Check verify(String signatureHeader, byte[] body, Instant now);

  /** @throws IllegalArgumentException when the body is not an event this adapter understands */
  WebhookEvent parse(byte[] body);
}
