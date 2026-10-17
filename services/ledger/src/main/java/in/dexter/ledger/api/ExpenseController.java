package in.dexter.ledger.api;

import in.dexter.core.error.CoreErrors;
import in.dexter.core.error.DexterException;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.db.page.PageRequest;
import in.dexter.idempotency.web.IdempotencyKey;
import in.dexter.idempotency.web.IdempotentResponses;
import in.dexter.ledger.app.DailyTotalsStore.DayTotal;
import in.dexter.ledger.app.ExpenseFilter;
import in.dexter.ledger.app.ExpensePatch;
import in.dexter.ledger.app.ExpenseService;
import in.dexter.ledger.app.ExpenseView;
import in.dexter.ledger.app.NewExpense;
import in.dexter.security.AuthContext;
import in.dexter.web.etag.ETags;
import in.dexter.web.etag.IfMatchVersion;
import in.dexter.web.page.PageResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * /v1/expenses (MASTER_PLAN P07). Create needs an Idempotency-Key; reads are keyset-paged; PATCH is a JSON Merge
 * Patch with If-Match; DELETE is soft and {@code POST /v1/expenses/{id}:restore} undoes it. Every response that
 * carries one expense carries its ETag.
 */
@RestController
@RequestMapping("/v1/expenses")
public class ExpenseController {
  static final String MERGE_PATCH = "application/merge-patch+json";
  static final Set<String> PATCHABLE = Set.of("occurredAt", "amountPaise", "merchant", "categoryPath", "paymentMethod",
      "userCardId", "note", "tags");
  private final ExpenseService expenses;

  public ExpenseController(ExpenseService expenses) {
    this.expenses = expenses;
  }

  @PostMapping
  public ResponseEntity<String> create(AuthContext caller, @IdempotencyKey(required = true) UUID key,
      @RequestBody NewExpense body) {
    return IdempotentResponses.toEntity(expenses.create(Callers.user(caller), key, body));
  }

  @GetMapping
  public PageResponse<ExpenseView> list(AuthContext caller, PageRequest page,
      @RequestParam(value = "from", required = false) String from,
      @RequestParam(value = "to", required = false) String to,
      @RequestParam(value = "category", required = false) String category,
      @RequestParam(value = "paymentMethod", required = false) String paymentMethod,
      @RequestParam(value = "cardId", required = false) UUID cardId,
      @RequestParam(value = "merchantId", required = false) UUID merchantId,
      @RequestParam(value = "minPaise", required = false) Long minPaise,
      @RequestParam(value = "maxPaise", required = false) Long maxPaise) {
    List<FieldError> errors = new ArrayList<>();
    ExpenseFilter f = new ExpenseFilter(date("from", from, errors), date("to", to, errors), category, paymentMethod,
        cardId, merchantId, minPaise, maxPaise);
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    return PageResponse.from(expenses.list(Callers.user(caller), page, f));
  }

  /** Text search within a date window (default: the last 12 months), newest first, keyset-paged (P08). */
  @GetMapping("/search")
  public PageResponse<ExpenseView> search(AuthContext caller, PageRequest page, @RequestParam("q") String q,
      @RequestParam(value = "from", required = false) String from,
      @RequestParam(value = "to", required = false) String to) {
    List<FieldError> errors = new ArrayList<>();
    LocalDate f = date("from", from, errors);
    LocalDate t = date("to", to, errors);
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    return PageResponse.from(expenses.search(Callers.user(caller), page, q, f, t));
  }

  /** Daily totals per category root (the trail's live panel; P08's /v1/summary builds on the same table). */
  @GetMapping("/daily-totals")
  public Map<String, List<DayTotal>> dailyTotals(AuthContext caller, @RequestParam("from") String from,
      @RequestParam("to") String to) {
    List<FieldError> errors = new ArrayList<>();
    LocalDate f = date("from", from, errors);
    LocalDate t = date("to", to, errors);
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    return Map.of("data", expenses.dailyTotals(Callers.user(caller), f, t));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ExpenseView> get(AuthContext caller, @PathVariable("id") UUID id) {
    ExpenseView v = expenses.get(Callers.user(caller), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @PatchMapping(value = "/{id}", consumes = {MERGE_PATCH, "application/json"})
  public ResponseEntity<ExpenseView> patch(AuthContext caller, @PathVariable("id") UUID id,
      @IfMatchVersion long version, @RequestBody JsonNode body) {
    ExpenseView v = expenses.patch(Callers.user(caller), id, version, toPatch(body));
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(AuthContext caller, @PathVariable("id") UUID id) {
    expenses.delete(Callers.user(caller), id);
    return ResponseEntity.noContent().build();
  }

  /** {@code POST /v1/expenses/{id}:restore} (AIP-136 custom method). */
  @PostMapping("/{idAction}")
  public ResponseEntity<ExpenseView> action(AuthContext caller, @PathVariable("idAction") String idAction) {
    int colon = idAction.indexOf(':');
    if (colon < 0 || !idAction.substring(colon + 1).equals("restore")) {
      throw new DexterException(CoreErrors.NOT_FOUND, "Unknown expense action");
    }
    UUID id;
    try {
      id = UUID.fromString(idAction.substring(0, colon));
    } catch (IllegalArgumentException e) {
      throw new DexterException(CoreErrors.INVALID_ID, "Not an expense id");
    }
    ExpenseView v = expenses.restore(Callers.user(caller), id);
    return ResponseEntity.ok().eTag(ETags.of(v.version())).body(v);
  }

  // ------------------------------------------------------------------------------------------- body parsing

  static ExpensePatch toPatch(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new DexterException(CoreErrors.BAD_REQUEST, "The body must be a JSON object (merge patch)");
    }
    for (String name : body.propertyNames()) {
      if (!PATCHABLE.contains(name)) {
        throw new DexterException(CoreErrors.UNKNOWN_FIELD, "Field '" + name + "' cannot be changed");
      }
    }
    List<FieldError> errors = new ArrayList<>();
    ExpensePatch p = new ExpensePatch(
        field(body, "occurredAt", n -> Instant.parse(n.asString()), errors),
        field(body, "amountPaise", n -> {
          if (!n.isIntegralNumber() || !n.canConvertToLong()) {
            throw new IllegalArgumentException();
          }
          return n.longValue();
        }, errors),
        field(body, "merchant", JsonNode::asString, errors),
        field(body, "categoryPath", JsonNode::asString, errors),
        field(body, "paymentMethod", JsonNode::asString, errors),
        field(body, "userCardId", n -> UUID.fromString(n.asString()), errors),
        field(body, "note", JsonNode::asString, errors),
        field(body, "tags", n -> {
          if (!n.isArray()) {
            throw new IllegalArgumentException();
          }
          List<String> out = new ArrayList<>();
          n.forEach(t -> {
            if (!t.isString()) {
              throw new IllegalArgumentException();
            }
            out.add(t.asString());
          });
          return out;
        }, errors));
    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    return p;
  }

  private interface Parse<T> {
    T apply(JsonNode n);
  }

  private static <T> ExpensePatch.Field<T> field(JsonNode body, String name, Parse<T> parse, List<FieldError> errors) {
    if (!body.has(name)) {
      return ExpensePatch.Field.absent();
    }
    JsonNode n = body.get(name);
    if (n.isNull()) {
      return ExpensePatch.Field.of(null);
    }
    boolean scalarText = !name.equals("amountPaise") && !name.equals("tags");
    try {
      if (scalarText && !n.isString()) {
        throw new IllegalArgumentException();
      }
      return ExpensePatch.Field.of(parse.apply(n));
    } catch (RuntimeException e) {
      errors.add(new FieldError(name, "TYPE_MISMATCH", "has the wrong type or format"));
      return ExpensePatch.Field.absent();
    }
  }

  static LocalDate date(String field, String v, List<FieldError> errors) {
    if (v == null || v.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(v);
    } catch (DateTimeParseException e) {
      errors.add(new FieldError(field, "INVALID_DATE", "A date like 2026-10-17"));
      return null;
    }
  }
}
