package in.dexter.ledger.api;

import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.ledger.app.CategoryService;
import in.dexter.security.AuthContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /v1/categories: the system tree + the caller's custom subtrees (small: not paged); custom categories. */
@RestController
@RequestMapping("/v1/categories")
public class CategoryController {
  private final CategoryService categories;

  public CategoryController(CategoryService categories) {
    this.categories = categories;
  }

  @GetMapping
  public Map<String, List<CategoryService.CategoryView>> list(AuthContext caller) {
    return Map.of("data", categories.list(Callers.user(caller)));
  }

  /** Idempotency-Key optional (a repeat without a key answers 409 CATEGORY_EXISTS). */
  @PostMapping
  public ResponseEntity<String> create(AuthContext caller, @IdempotencyKey UUID key,
      @RequestBody CategoryService.NewCategory body) {
    return IdempotentResponses.toEntity(categories.create(Callers.user(caller), key, body));
  }
}
