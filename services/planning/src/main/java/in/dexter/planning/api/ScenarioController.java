package in.dexter.planning.api;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.db.page.PageRequest;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.planning.app.ScenarioService;
import in.dexter.security.AuthContext;
import in.dexter.web.etag.ETags;
import in.dexter.web.etag.IfMatchVersion;
import in.dexter.web.page.PageResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * /v1/scenarios (MASTER_PLAN P13): CRUD plus {@code POST /v1/scenarios/{id}:evaluate}. Evaluating stores nothing —
 * it applies the saved adjustments to the snapshot taken when the scenario was saved — so it needs no
 * Idempotency-Key.
 */
@RestController
@RequestMapping(ScenarioService.PATH)
public class ScenarioController {
  private final ScenarioService scenarios;

  public ScenarioController(ScenarioService scenarios) {
    this.scenarios = scenarios;
  }

  @PostMapping
  public ResponseEntity<String> create(AuthContext caller, @IdempotencyKey(required = true) UUID key,
      @RequestBody ScenarioService.NewScenario body) {
    return IdempotentResponses.toEntity(scenarios.create(Callers.user(caller), key, body));
  }

  @GetMapping
  public PageResponse<ScenarioService.ScenarioView> list(AuthContext caller, PageRequest page) {
    return PageResponse.from(scenarios.list(Callers.user(caller), page));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ScenarioService.ScenarioView> get(AuthContext caller, @PathVariable("id") UUID id) {
    ScenarioService.ScenarioView v = scenarios.get(Callers.user(caller), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @PatchMapping(value = "/{id}", consumes = {BudgetController.MERGE_PATCH, "application/json"})
  public ResponseEntity<ScenarioService.ScenarioView> patch(AuthContext caller, @PathVariable("id") UUID id,
      @IfMatchVersion long version, @RequestBody JsonNode body) {
    ScenarioService.ScenarioView v = scenarios.patch(Callers.user(caller), id, version, body);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  /** {@code {id}:evaluate} — the only action, and a pure read. */
  @PostMapping("/{idAction}")
  public ScenarioService.EvaluationView act(AuthContext caller, @PathVariable("idAction") String idAction) {
    int colon = idAction.indexOf(':');
    String action = colon < 0 ? "" : idAction.substring(colon + 1);
    UUID id = Callers.id(colon < 0 ? idAction : idAction.substring(0, colon));
    if (!"evaluate".equals(action)) {
      throw new DexterException(CoreErrors.NOT_FOUND, "Unknown scenario action");
    }
    return scenarios.evaluate(Callers.user(caller), id);
  }
}
