package in.dexter.ledger.api;

import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.ledger.app.BulkExpenseService;
import in.dexter.security.AuthContext;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /v1/expenses:bulk} (AIP-136 custom method on the collection; Idempotency-Key required). */
@RestController
public class BulkController {
  private final BulkExpenseService bulk;

  public BulkController(BulkExpenseService bulk) {
    this.bulk = bulk;
  }

  @PostMapping("/v1/expenses:bulk")
  public ResponseEntity<String> bulk(AuthContext caller, @IdempotencyKey(required = true) UUID key,
      @RequestBody BulkExpenseService.BulkRequest body) {
    return IdempotentResponses.toEntity(bulk.create(Callers.user(caller), key, body));
  }
}
