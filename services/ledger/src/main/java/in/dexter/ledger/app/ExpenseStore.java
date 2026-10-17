package in.dexter.ledger.app;

import in.dexter.db.page.Page;
import in.dexter.db.page.PageRequest;
import java.time.Instant;
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
}
