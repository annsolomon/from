package in.dexter.ledger.app;

import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.core.error.FieldError;
import in.dexter.core.error.ValidationException;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.tenant.TenantTx;
import in.dexter.events.OutboxWriter;
import in.dexter.idempotency.ApiResponse;
import in.dexter.idempotency.IdempotencyEngine;
import in.dexter.idempotency.IdempotentRequest;
import in.dexter.idempotency.IdempotentResponse;
import in.dexter.ledger.domain.DailyDelta;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /v1/expenses:bulk} (MASTER_PLAN P08): up to {@value #MAX_ITEMS} expenses in one transaction with a
 * per-item result. Idempotency-Key required (a retried call replays the same answer); {@code clientRef} is the
 * natural key per item, so an item whose reference already exists — from an earlier call, an import, or earlier in
 * this same request — is reported {@code duplicate} with the existing id instead of being created again. Invalid
 * items (field errors, unknown category) are reported {@code invalid} and do not stop the valid ones. The created
 * rows go in with multi-row INSERTs; daily totals get one change per bucket; the {@code ledger.expense.created}
 * events are written with one batched outbox insert.
 */
public final class BulkExpenseService {
  public static final String PATH = "/v1/expenses:bulk";
  public static final int MAX_ITEMS = 500;

  public record BulkRequest(List<NewExpense> items) {}

  /** {@code status}: created | duplicate | invalid. */
  public record ItemResult(int index, String status, UUID id, List<FieldError> errors) {}

  public record BulkResponse(int created, int duplicates, int invalid, List<ItemResult> results) {}

  private final TenantTx tx;
  private final IdempotencyEngine idempotency;
  private final ExpenseStore expenses;
  private final DailyTotalsStore daily;
  private final CategoryStore categories;
  private final OutboxWriter outbox;
  private final MerchantResolver resolver;
  private final ObjectMapper mapper;
  private final Clock clock;

  public BulkExpenseService(TenantTx tx, IdempotencyEngine idempotency, ExpenseStore expenses, DailyTotalsStore daily,
      CategoryStore categories, OutboxWriter outbox, MerchantResolver resolver, ObjectMapper mapper, Clock clock) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.expenses = expenses;
    this.daily = daily;
    this.categories = categories;
    this.outbox = outbox;
    this.resolver = resolver;
    this.mapper = mapper;
    this.clock = clock;
  }

  public IdempotentResponse create(Tenant tenant, UUID key, BulkRequest req) {
    List<NewExpense> items = req == null ? null : req.items();
    if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
      throw new ValidationException(List.of(new FieldError("items", items == null || items.isEmpty() ? "REQUIRED"
          : "TOO_MANY", "1-" + MAX_ITEMS + " expenses")));
    }
    // field validation first (no database): invalid items never reach the transaction
    Map<Integer, ExpenseInput.Valid> valid = new LinkedHashMap<>();
    Map<Integer, List<FieldError>> invalid = new HashMap<>();
    for (int i = 0; i < items.size(); i++) {
      List<FieldError> errors = new ArrayList<>();
      ExpenseInput.Valid v = ExpenseInput.validate(items.get(i), "items[" + i + "].", errors);
      if (v == null) {
        invalid.put(i, errors);
      } else {
        valid.put(i, v);
      }
    }
    IdempotentRequest fingerprint = IdempotentRequest.of("POST", PATH, req, mapper);
    Map<String, MerchantResolver.AliasMatch> aliases = resolver.prefetch(tenant,
        valid.values().stream().map(ExpenseInput.Valid::merchant).filter(m -> m != null).distinct().toList());
    return tx.write(tenant, () -> idempotency.execute(tenant, key, fingerprint,
        () -> ApiResponse.of(200, write(tenant, items.size(), valid, invalid, aliases))));
  }

  private BulkResponse write(Tenant tenant, int size, Map<Integer, ExpenseInput.Valid> valid,
      Map<Integer, List<FieldError>> fieldErrors, Map<String, MerchantResolver.AliasMatch> aliases) {
    Map<Integer, List<FieldError>> invalid = new HashMap<>(fieldErrors);
    Set<String> visible = new HashSet<>();
    categories.listVisible().forEach(c -> visible.add(c.path()));
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    MerchantResolver.Session session = resolver.session(aliases);

    Map<Integer, ExpenseRow> rows = new LinkedHashMap<>();
    Map<Integer, String> duplicateOfRef = new HashMap<>();
    Map<String, Integer> firstWithRef = new HashMap<>();
    for (Map.Entry<Integer, ExpenseInput.Valid> e : valid.entrySet()) {
      ExpenseInput.Valid v = e.getValue();
      if (v.category() != null && !visible.contains(v.category())) {
        invalid.put(e.getKey(), List.of(new FieldError("items[" + e.getKey() + "].categoryPath", "UNKNOWN_CATEGORY",
            "No such category (GET /v1/categories)")));
        continue;
      }
      if (v.clientRef() != null && firstWithRef.putIfAbsent(v.clientRef(), e.getKey()) != null) {
        duplicateOfRef.put(e.getKey(), v.clientRef()); // same reference earlier in this request
        continue;
      }
      rows.put(e.getKey(), ExpenseService.newRow(tenant.accountId(), v, session, "manual", "user", now));
    }

    Set<UUID> inserted = expenses.insertAll(new ArrayList<>(rows.values()));
    List<String> refsToLookUp = new ArrayList<>();
    rows.values().stream().filter(r -> !inserted.contains(r.id()) && r.clientRef() != null)
        .forEach(r -> refsToLookUp.add(r.clientRef()));
    refsToLookUp.addAll(duplicateOfRef.values());
    Map<String, UUID> existing = expenses.idsByClientRef(refsToLookUp);

    List<ExpenseRow> created = rows.values().stream().filter(r -> inserted.contains(r.id())).toList();
    daily.apply(tenant.accountId(), DailyDelta.sum(created.stream().map(ExpenseService::counted).toList()));
    outbox.appendAll(LedgerEvents.EXPENSE_CREATED, created.stream()
        .map(r -> new OutboxWriter.Pending<>(r.accountId(), r.id(), r.version(),
            new ExpenseChangedV1(r.id(), r.version(), null, ExpenseService.state(r), now.toString())))
        .toList());

    List<ItemResult> results = new ArrayList<>(size);
    int nCreated = 0;
    int nDup = 0;
    for (int i = 0; i < size; i++) {
      ExpenseRow row = rows.get(i);
      if (invalid.containsKey(i)) {
        results.add(new ItemResult(i, "invalid", null, invalid.get(i)));
      } else if (row != null && inserted.contains(row.id())) {
        results.add(new ItemResult(i, "created", row.id(), List.of()));
        nCreated++;
      } else {
        String ref = row != null ? row.clientRef() : duplicateOfRef.get(i);
        results.add(new ItemResult(i, "duplicate", existing.get(ref), List.of()));
        nDup++;
      }
    }
    return new BulkResponse(nCreated, nDup, invalid.size(), results);
  }
}
