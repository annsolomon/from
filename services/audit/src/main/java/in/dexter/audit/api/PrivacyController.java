package in.dexter.audit.api;

import in.dexter.audit.app.AuditViews.PrivacyView;
import in.dexter.audit.app.PrivacyService;
import in.dexter.audit.domain.PrivacySaga;
import in.dexter.db.page.PageRequest;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.security.AuthContext;
import in.dexter.security.web.RequiresStepUp;
import in.dexter.web.page.PageResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/privacy (MASTER_PLAN P18): ask for an export or a deletion (step-up, Idempotency-Key required; 202), follow
 * the requests. A completed export carries a presigned download link for 24 hours.
 */
@RestController
@RequestMapping(PrivacyService.PATH)
public class PrivacyController {
  private final PrivacyService privacy;

  public PrivacyController(PrivacyService privacy) {
    this.privacy = privacy;
  }

  @PostMapping("/export")
  @RequiresStepUp
  public ResponseEntity<String> export(AuthContext caller, @IdempotencyKey(required = true) UUID key) {
    return IdempotentResponses.toEntity(privacy.request(Callers.user(caller), key, PrivacySaga.EXPORT));
  }

  @PostMapping("/delete")
  @RequiresStepUp
  public ResponseEntity<String> delete(AuthContext caller, @IdempotencyKey(required = true) UUID key) {
    return IdempotentResponses.toEntity(privacy.request(Callers.user(caller), key, PrivacySaga.DELETE));
  }

  @GetMapping("/requests")
  public PageResponse<PrivacyView> list(AuthContext caller, PageRequest page) {
    return PageResponse.from(privacy.list(Callers.user(caller), page));
  }

  @GetMapping("/requests/{id}")
  public PrivacyView get(AuthContext caller, @PathVariable("id") UUID id) {
    return privacy.get(Callers.user(caller), id);
  }
}
