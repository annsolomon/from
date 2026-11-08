package in.dexter.commerce.api;

import in.dexter.commerce.app.ClickService;
import in.dexter.commerce.app.Viewer;
import in.dexter.security.web.PublicEndpoint;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /r/{token}} (MASTER_PLAN P17): the signed click redirect. 302 to the merchant (with the click id as
 * {@code subid}) or the ad's URL; never cached, and no referrer leaves for the merchant.
 */
@RestController
public class RedirectController {
  private final ClickService clicks;

  public RedirectController(ClickService clicks) {
    this.clicks = clicks;
  }

  @GetMapping("/r/{token}")
  @PublicEndpoint
  public ResponseEntity<Void> redirect(Viewer viewer, @PathVariable("token") String token) {
    return ResponseEntity.status(HttpStatus.FOUND).location(clicks.redirect(token, viewer))
        .cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").build();
  }
}
