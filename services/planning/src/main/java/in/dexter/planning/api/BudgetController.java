package in.dexter.planning.api;

import in.dexter.db.page.PageRequest;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.planning.app.BudgetService;
import in.dexter.security.AuthContext;
import in.dexter.web.etag.ETags;
import in.dexter.web.etag.IfMatchVersion;
import in.dexter.web.page.PageResponse;
import java.util.List;
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
 * /v1/budgets (MASTER_PLAN P13): CRUD including {@code GET /v1/budgets/{id}} (which the old repo lacked),
 * {@code /status} for every active budget's current period and {@code /{id}/history} for its past periods (keyset).
 */
@RestController
@RequestMapping(BudgetService.PATH)
public class BudgetController {
  static final String MERGE_PATCH = "application/merge-patch+json";

  private final BudgetService budgets;

  public BudgetController(BudgetService budgets) {
    this.budgets = budgets;
  }

  @PostMapping
  public ResponseEntity<String> create(AuthContext caller, @IdempotencyKey(required = true) UUID key,
      @RequestBody BudgetService.NewBudget body) {
    return IdempotentResponses.toEntity(budgets.create(Callers.user(caller), key, body));
  }

  @GetMapping
  public PageResponse<BudgetService.BudgetView> list(AuthContext caller, PageRequest page) {
    return PageResponse.from(budgets.list(Callers.user(caller), page));
  }

  /** Every active budget with its current period: what a dashboard draws. */
  @GetMapping("/status")
  public List<BudgetService.StatusView> status(AuthContext caller) {
    return budgets.status(Callers.user(caller));
  }

  @GetMapping("/{id}")
  public ResponseEntity<BudgetService.BudgetView> get(AuthContext caller, @PathVariable("id") UUID id) {
    BudgetService.BudgetView v = budgets.get(Callers.user(caller), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @GetMapping("/{id}/history")
  public PageResponse<BudgetService.PeriodView> history(AuthContext caller, @PathVariable("id") UUID id,
      PageRequest page) {
    return PageResponse.from(budgets.history(Callers.user(caller), id, page));
  }

  @PatchMapping(value = "/{id}", consumes = {MERGE_PATCH, "application/json"})
  public ResponseEntity<BudgetService.BudgetView> patch(AuthContext caller, @PathVariable("id") UUID id,
      @IfMatchVersion long version, @RequestBody JsonNode body) {
    BudgetService.BudgetView v = budgets.patch(Callers.user(caller), id, version, body);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  /** Ends the budget; its periods and alerts stay for the history. */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> end(AuthContext caller, @PathVariable("id") UUID id) {
    budgets.end(Callers.user(caller), id);
    return ResponseEntity.noContent().build();
  }
}
