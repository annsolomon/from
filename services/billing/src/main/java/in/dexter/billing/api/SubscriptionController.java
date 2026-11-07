package in.dexter.billing.api;

import in.dexter.billing.app.BillingViews.SubscriptionView;
import in.dexter.billing.app.CheckoutService;
import in.dexter.billing.app.SubscriptionService;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.security.AuthContext;
import in.dexter.security.web.RequiresStepUp;
import in.dexter.web.etag.ETags;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/subscriptions (MASTER_PLAN P16): checkout (Idempotency-Key required, step-up, Mode B), the account's own
 * subscription, and cancel at the end of the period (step-up; a key is optional). Both mutations are billing changes,
 * which MASTER_PLAN 7.1 lists as sensitive.
 */
@RestController
@RequestMapping("/v1/subscriptions")
public class SubscriptionController {
  private final CheckoutService checkout;
  private final SubscriptionService subscriptions;

  public SubscriptionController(CheckoutService checkout, SubscriptionService subscriptions) {
    this.checkout = checkout;
    this.subscriptions = subscriptions;
  }

  @PostMapping("/checkout")
  @RequiresStepUp
  public ResponseEntity<String> checkout(AuthContext caller, @IdempotencyKey(required = true) UUID key,
      @RequestBody CheckoutService.Checkout body) {
    return IdempotentResponses.toEntity(checkout.checkout(Callers.user(caller), key, body));
  }

  @GetMapping("/me")
  public ResponseEntity<SubscriptionView> me(AuthContext caller) {
    SubscriptionView v = subscriptions.me(Callers.user(caller));
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @PostMapping("/me:cancel")
  @RequiresStepUp
  public ResponseEntity<String> cancel(AuthContext caller, @IdempotencyKey(required = false) UUID key) {
    return IdempotentResponses.toEntity(subscriptions.cancel(Callers.user(caller), key));
  }
}
