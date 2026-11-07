package in.dexter.billing.api;

import in.dexter.billing.app.BillingViews.EntitlementsView;
import in.dexter.billing.app.EntitlementService;
import in.dexter.security.AuthContext;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /v1/entitlements/me (MASTER_PLAN P16): what the account may use now; cached 60 s per account. */
@RestController
public class EntitlementController {
  private final EntitlementService entitlements;

  public EntitlementController(EntitlementService entitlements) {
    this.entitlements = entitlements;
  }

  @GetMapping(EntitlementService.PATH)
  public ResponseEntity<EntitlementsView> me(AuthContext caller) {
    return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePrivate())
        .body(entitlements.me(Callers.user(caller)));
  }
}
