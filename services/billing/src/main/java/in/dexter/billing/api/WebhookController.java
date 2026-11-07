package in.dexter.billing.api;

import in.dexter.billing.app.BillingViews.WebhookAck;
import in.dexter.billing.app.WebhookService;
import in.dexter.security.web.PublicEndpoint;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /v1/webhooks/{provider} (MASTER_PLAN P16, 7.3). Public - the provider's HMAC signature over the raw bytes is
 * the credential, so the body is taken as bytes and never re-serialised before it is checked.
 */
@RestController
@PublicEndpoint
public class WebhookController {
  private final WebhookService webhooks;

  public WebhookController(WebhookService webhooks) {
    this.webhooks = webhooks;
  }

  @PostMapping(WebhookService.PATH + "/{provider}")
  public WebhookAck receive(@PathVariable("provider") String provider,
      @RequestHeader(value = "Webhook-Signature", required = false) String signature,
      @RequestBody(required = false) byte[] body) {
    return webhooks.receive(provider, signature, body == null ? new byte[0] : body);
  }
}
