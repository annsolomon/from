package in.dexter.billing.api;

import in.dexter.billing.app.BillingViews.PlanView;
import in.dexter.billing.app.PlanService;
import in.dexter.security.web.PublicEndpoint;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /v1/plans (MASTER_PLAN P16): the price list, public like the catalog. At most three rows, so not paged (A-93). */
@RestController
@PublicEndpoint
@RequestMapping(PlanService.PATH)
public class PlanController {
  private final PlanService plans;

  public PlanController(PlanService plans) {
    this.plans = plans;
  }

  @GetMapping
  public ResponseEntity<Map<String, List<PlanView>>> list() {
    return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
        .body(Map.of("data", plans.list()));
  }

  @GetMapping("/{code}")
  public ResponseEntity<PlanView> get(@PathVariable("code") String code) {
    return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
        .body(plans.get(code));
  }
}
