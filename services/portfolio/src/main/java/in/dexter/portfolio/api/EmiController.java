package in.dexter.portfolio.api;

import in.dexter.db.page.PageRequest;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.portfolio.app.EmiService;
import in.dexter.security.AuthContext;
import in.dexter.web.etag.ETags;
import in.dexter.web.etag.IfMatchVersion;
import in.dexter.web.page.PageResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** /v1/emi-plans (MASTER_PLAN P11): CRUD (create needs an Idempotency-Key, PATCH If-Match) and the schedule. */
@RestController
@RequestMapping("/v1/emi-plans")
public class EmiController {
  private final EmiService plans;

  public EmiController(EmiService plans) {
    this.plans = plans;
  }

  @PostMapping
  public ResponseEntity<String> create(AuthContext caller, @IdempotencyKey(required = true) UUID key,
      @RequestBody EmiService.NewPlan body) {
    return IdempotentResponses.toEntity(plans.create(Callers.user(caller), key, body));
  }

  @GetMapping
  public PageResponse<EmiService.PlanView> list(AuthContext caller, PageRequest page) {
    return PageResponse.from(plans.list(Callers.user(caller), page));
  }

  @GetMapping("/{id}")
  public ResponseEntity<EmiService.PlanView> get(AuthContext caller, @PathVariable("id") UUID id) {
    EmiService.PlanView v = plans.get(Callers.user(caller), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @PatchMapping(value = "/{id}", consumes = {CardController.MERGE_PATCH, "application/json"})
  public ResponseEntity<EmiService.PlanView> patch(AuthContext caller, @PathVariable("id") UUID id,
      @IfMatchVersion long version, @RequestBody JsonNode body) {
    EmiService.PlanView v = plans.patch(Callers.user(caller), id, version, body);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> close(AuthContext caller, @PathVariable("id") UUID id) {
    plans.close(Callers.user(caller), id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/schedule")
  public PageResponse<EmiService.InstallmentView> schedule(AuthContext caller, @PathVariable("id") UUID id,
      PageRequest page) {
    return PageResponse.from(plans.schedule(Callers.user(caller), id, page));
  }
}
