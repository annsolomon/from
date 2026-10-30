package in.dexter.planning.api;

import in.dexter.db.page.PageRequest;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.planning.app.LiabilityService;
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

/**
 * /v1/liabilities (MASTER_PLAN P13): CRUD (create needs an Idempotency-Key, PATCH an If-Match) and the stored
 * amortization schedule (keyset, in instalment order). DELETE closes the liability.
 */
@RestController
@RequestMapping(LiabilityService.PATH)
public class LiabilityController {
  private final LiabilityService liabilities;

  public LiabilityController(LiabilityService liabilities) {
    this.liabilities = liabilities;
  }

  @PostMapping
  public ResponseEntity<String> create(AuthContext caller, @IdempotencyKey(required = true) UUID key,
      @RequestBody LiabilityService.NewLiability body) {
    return IdempotentResponses.toEntity(liabilities.create(Callers.user(caller), key, body));
  }

  @GetMapping
  public PageResponse<LiabilityService.LiabilityView> list(AuthContext caller, PageRequest page) {
    return PageResponse.from(liabilities.list(Callers.user(caller), page));
  }

  @GetMapping("/{id}")
  public ResponseEntity<LiabilityService.LiabilityView> get(AuthContext caller, @PathVariable("id") UUID id) {
    LiabilityService.LiabilityView v = liabilities.get(Callers.user(caller), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @GetMapping("/{id}/schedule")
  public PageResponse<LiabilityService.InstallmentView> schedule(AuthContext caller, @PathVariable("id") UUID id,
      PageRequest page) {
    return PageResponse.from(liabilities.schedule(Callers.user(caller), id, page));
  }

  @PatchMapping(value = "/{id}", consumes = {BudgetController.MERGE_PATCH, "application/json"})
  public ResponseEntity<LiabilityService.LiabilityView> patch(AuthContext caller, @PathVariable("id") UUID id,
      @IfMatchVersion long version, @RequestBody JsonNode body) {
    LiabilityService.LiabilityView v = liabilities.patch(Callers.user(caller), id, version, body);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> close(AuthContext caller, @PathVariable("id") UUID id) {
    liabilities.close(Callers.user(caller), id);
    return ResponseEntity.noContent().build();
  }
}
