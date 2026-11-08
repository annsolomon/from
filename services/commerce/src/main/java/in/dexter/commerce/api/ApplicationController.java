package in.dexter.commerce.api;

import in.dexter.commerce.app.ApplicationService;
import in.dexter.commerce.app.CommerceViews.ApplicationView;
import in.dexter.db.page.PageRequest;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.security.AuthContext;
import in.dexter.web.etag.ETags;
import in.dexter.web.page.PageResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /v1/card-applications (MASTER_PLAN P17): apply (Idempotency-Key required), list, read, withdraw. */
@RestController
@RequestMapping(ApplicationService.PATH)
public class ApplicationController {
  private final ApplicationService apps;

  public ApplicationController(ApplicationService apps) {
    this.apps = apps;
  }

  @PostMapping
  public ResponseEntity<String> apply(AuthContext caller, @IdempotencyKey(required = true) UUID key,
      @RequestBody ApplicationService.NewApplication body) {
    return IdempotentResponses.toEntity(apps.apply(Callers.user(caller), key, body));
  }

  @GetMapping
  public PageResponse<ApplicationView> list(AuthContext caller, PageRequest page) {
    return PageResponse.from(apps.list(Callers.user(caller), page));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApplicationView> get(AuthContext caller, @PathVariable("id") UUID id) {
    ApplicationView v = apps.get(Callers.user(caller), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @PostMapping("/{id}:withdraw")
  public ResponseEntity<ApplicationView> withdraw(AuthContext caller, @PathVariable("id") UUID id) {
    ApplicationView v = apps.withdraw(Callers.user(caller), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }
}
