package in.dexter.ledger.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@code ledger.expenses} of the current tenant (every method needs a TenantTx). */
public interface ExpenseStore {
  /** Inserts; a duplicate {@code (account, client_ref)} throws {@code CLIENT_REF_TAKEN}. */
  void insert(ExpenseRow row);

  /** Including soft-deleted rows. */
  Optional<ExpenseRow> find(UUID id);

  /** {@code SELECT ... FOR UPDATE}: serialises concurrent writes to one expense. */
  Optional<ExpenseRow> lock(UUID id);

  /** Writes every mutable field and the new version; {@code deleted_at} as given. */
  void update(ExpenseRow row);

  Page<ExpenseRow> list(PageRequest page, ExpenseFilter filter, Instant fromInclusive, Instant toExclusive);

  /**
   * {@code GET /v1/expenses/search}: rows in {@code [from, to)} whose merchant or note contains {@code q} (or whose
   * merchant is word-similar to it). {@code filters} is what the cursor is bound to.
   */
  Page<ExpenseRow> search(PageRequest page, String q, Instant fromInclusive, Instant toExclusive,
      Map<String, ?> filters);

  /**
   * Multi-row insert; a row whose {@code (account, client_ref)} already exists is skipped (natural idempotency of
   * bulk create and import). Returns the ids actually inserted.
   */
  java.util.Set<UUID> insertAll(List<ExpenseRow> rows);

  /** Existing expenses (live or deleted) by client reference. */
  Map<String, UUID> idsByClientRef(Collection<String> refs);

  /** One group of {@code GET /v1/summary} computed from expenses (merchant / payment method). */
  record Group(String key, String label, long totalPaise, int txnCount) {}

  /** Live expenses in {@code [from, to)} grouped by {@code merchant} or {@code paymentMethod}, largest first. */
  List<Group> groups(String by, Instant fromInclusive, Instant toExclusive, int limit);
}
