package in.dexter.portfolio.api;

import in.dexter.db.page.PageRequest;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.portfolio.app.CardService;
import in.dexter.portfolio.app.CardView;
import in.dexter.portfolio.app.StatementView;
import in.dexter.portfolio.app.TransactionService;
import in.dexter.portfolio.app.TxnView;
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
 * /v1/cards (MASTER_PLAN P11): the user's cards (create needs an Idempotency-Key; PATCH is a JSON Merge Patch with
 * If-Match; DELETE removes softly), their statements and transactions (keyset, newest first; recording a
 * transaction needs an Idempotency-Key).
 */
@RestController
@RequestMapping("/v1/cards")
public class CardController {
  static final String MERGE_PATCH = "application/merge-patch+json";
  private final CardService cards;
  private final TransactionService txns;

  public CardController(CardService cards, TransactionService txns) {
    this.cards = cards;
    this.txns = txns;
  }

  @PostMapping
  public ResponseEntity<String> create(AuthContext caller, @IdempotencyKey(required = true) UUID key,
      @RequestBody CardService.NewCard body) {
    return IdempotentResponses.toEntity(cards.create(Callers.user(caller), key, body));
  }

  @GetMapping
  public PageResponse<CardView> list(AuthContext caller, PageRequest page) {
    return PageResponse.from(cards.list(Callers.user(caller), page));
  }

  @GetMapping("/{id}")
  public ResponseEntity<CardView> get(AuthContext caller, @PathVariable("id") UUID id) {
    CardView v = cards.get(Callers.user(caller), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @PatchMapping(value = "/{id}", consumes = {MERGE_PATCH, "application/json"})
  public ResponseEntity<CardView> patch(AuthContext caller, @PathVariable("id") UUID id, @IfMatchVersion long version,
      @RequestBody JsonNode body) {
    CardView v = cards.patch(Callers.user(caller), id, version, body);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> remove(AuthContext caller, @PathVariable("id") UUID id) {
    cards.remove(Callers.user(caller), id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/statements")
  public PageResponse<StatementView> statements(AuthContext caller, @PathVariable("id") UUID id, PageRequest page) {
    return PageResponse.from(txns.statements(Callers.user(caller), id, page));
  }

  @GetMapping("/{id}/transactions")
  public PageResponse<TxnView> transactions(AuthContext caller, @PathVariable("id") UUID id, PageRequest page) {
    return PageResponse.from(txns.list(Callers.user(caller), id, page));
  }

  @PostMapping("/{id}/transactions")
  public ResponseEntity<String> record(AuthContext caller, @PathVariable("id") UUID id,
      @IdempotencyKey(required = true) UUID key, @RequestBody TransactionService.NewTransaction body) {
    return IdempotentResponses.toEntity(txns.record(Callers.user(caller), id, key, body));
  }

  @GetMapping("/{id}/transactions/{txnId}")
  public TxnView transaction(AuthContext caller, @PathVariable("id") UUID id, @PathVariable("txnId") UUID txnId) {
    return txns.get(Callers.user(caller), id, txnId);
  }
}
