package in.dexter.ledger.api;

import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.ledger.app.RuleService;
import in.dexter.security.AuthContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /v1/rules: the caller's categorisation rules (≤ 200, not paged), priority order. */
@RestController
@RequestMapping("/v1/rules")
public class RuleController {
  private final RuleService rules;

  public RuleController(RuleService rules) {
    this.rules = rules;
  }

  @GetMapping
  public Map<String, List<RuleService.RuleView>> list(AuthContext caller) {
    return Map.of("data", rules.list(Callers.user(caller)));
  }

  /** Idempotency-Key optional. */
  @PostMapping
  public ResponseEntity<String> create(AuthContext caller, @IdempotencyKey UUID key,
      @RequestBody RuleService.NewRule body) {
    return IdempotentResponses.toEntity(rules.create(Callers.user(caller), key, body));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(AuthContext caller, @PathVariable("id") UUID id) {
    rules.delete(Callers.user(caller), id);
    return ResponseEntity.noContent().build();
  }
}
