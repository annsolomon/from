package in.dexter.commerce.api;

import in.dexter.commerce.app.CommerceViews.PostbackResult;
import in.dexter.commerce.app.PostbackService;
import in.dexter.security.web.PublicEndpoint;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/webhooks/affiliate/{network}} (MASTER_PLAN P17): affiliate conversion postbacks. No token: the
 * network authenticates with its HMAC signature over the raw body, which is why the body is taken as bytes.
 */
@RestController
@RequestMapping(PostbackService.PATH)
public class PostbackController {
  private final PostbackService postbacks;

  public PostbackController(PostbackService postbacks) {
    this.postbacks = postbacks;
  }

  @PostMapping("/{network}")
  @PublicEndpoint
  public PostbackResult receive(@PathVariable("network") String network,
      @RequestHeader(value = "Postback-Signature", required = false) String signature, @RequestBody byte[] body) {
    return postbacks.receive(network, signature, body);
  }
}
