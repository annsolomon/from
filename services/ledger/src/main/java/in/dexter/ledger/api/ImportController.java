package in.dexter.ledger.api;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.ledger.app.ImportService;
import in.dexter.security.AuthContext;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/imports (P08): create a job (201 + presigned upload), {@code POST /v1/imports/{id}:start} (202) once the file
 * is uploaded, {@code GET /v1/imports/{id}} for status, counts and the first row errors.
 */
@RestController
@RequestMapping("/v1/imports")
public class ImportController {
  private final ImportService imports;

  public ImportController(ImportService imports) {
    this.imports = imports;
  }

  /** Idempotency-Key optional. */
  @PostMapping
  public ResponseEntity<String> create(AuthContext caller, @IdempotencyKey UUID key,
      @RequestBody ImportService.NewImport body) {
    return IdempotentResponses.toEntity(imports.create(Callers.user(caller), key, body));
  }

  @PostMapping("/{idAction}")
  public ResponseEntity<ImportService.ImportView> action(AuthContext caller,
      @PathVariable("idAction") String idAction) {
    int colon = idAction.indexOf(':');
    if (colon < 0 || !idAction.substring(colon + 1).equals("start")) {
      throw new DexterException(CoreErrors.NOT_FOUND, "Unknown import action");
    }
    UUID id;
    try {
      id = UUID.fromString(idAction.substring(0, colon));
    } catch (IllegalArgumentException e) {
      throw new DexterException(CoreErrors.INVALID_ID, "Not an import id");
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(imports.start(Callers.user(caller), id));
  }

  @GetMapping("/{id}")
  public ImportService.ImportView get(AuthContext caller, @PathVariable("id") UUID id) {
    return imports.get(Callers.user(caller), id);
  }
}
