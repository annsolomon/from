package in.dexter.portfolio.api;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.db.page.PageRequest;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.portfolio.app.LineView;
import in.dexter.portfolio.app.StatementService;
import in.dexter.portfolio.app.UploadView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/statements/uploads (MASTER_PLAN P12): create an upload (Idempotency-Key required; returns the presigned PUT),
 * {@code :submit} (Idempotency-Key required; scan + hand-off to the ai-parser, 202), review (detail, lines keyset in
 * statement order), {@code :confirm} (Idempotency-Key optional; lines become card transactions) and {@code :discard}.
 */
@RestController
@RequestMapping(StatementService.PATH)
public class StatementController {
  private final StatementService statements;

  public StatementController(StatementService statements) {
    this.statements = statements;
  }

  @PostMapping
  public ResponseEntity<String> create(AuthContext caller, @IdempotencyKey(required = true) UUID key,
      @RequestBody StatementService.NewUpload body) {
    return IdempotentResponses.toEntity(statements.create(Callers.user(caller), key, body));
  }

  @GetMapping
  public PageResponse<UploadView> list(AuthContext caller, PageRequest page,
      @RequestParam(name = "cardId", required = false) UUID cardId) {
    return PageResponse.from(statements.list(Callers.user(caller), cardId, page));
  }

  @GetMapping("/{id}")
  public ResponseEntity<UploadView> get(AuthContext caller, @PathVariable("id") UUID id) {
    UploadView v = statements.get(Callers.user(caller), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @GetMapping("/{id}/lines")
  public PageResponse<LineView> lines(AuthContext caller, @PathVariable("id") UUID id, PageRequest page) {
    return PageResponse.from(statements.lines(Callers.user(caller), id, page));
  }

  /** {@code {id}:submit} (key required), {@code {id}:confirm} (key optional, body optional), {@code {id}:discard}. */
  @PostMapping("/{idAction}")
  public ResponseEntity<?> act(AuthContext caller, @PathVariable("idAction") String idAction,
      @IdempotencyKey UUID key, @RequestBody(required = false) StatementService.Confirm body) {
    int colon = idAction.indexOf(':');
    String action = colon < 0 ? "" : idAction.substring(colon + 1);
    UUID id = Callers.id(colon < 0 ? idAction : idAction.substring(0, colon));
    return switch (action) {
      case "submit" -> {
        if (key == null) {
          throw new DexterException(CoreErrors.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key header is required");
        }
        yield IdempotentResponses.toEntity(statements.submit(Callers.user(caller), id, key));
      }
      case "confirm" -> IdempotentResponses.toEntity(statements.confirm(Callers.user(caller), id, key, body));
      case "discard" -> ResponseEntity.ok(statements.discard(Callers.user(caller), id));
      default -> throw new DexterException(CoreErrors.NOT_FOUND, "Unknown statement upload action");
    };
  }
}
