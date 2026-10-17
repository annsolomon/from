package in.dexter.ledger.app;

import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.events.EventType;
import java.util.List;

/** Ledger's event types (contracts/events/ledger.expense.*.v1.json), topic {@code dexter.ledger.expense.v1}. */
public final class LedgerEvents {
  public static final EventType<ExpenseChangedV1> EXPENSE_CREATED =
      EventType.of("ledger", "expense", "created", ExpenseChangedV1.VERSION, ExpenseChangedV1.class);
  public static final EventType<ExpenseChangedV1> EXPENSE_UPDATED =
      EventType.of("ledger", "expense", "updated", ExpenseChangedV1.VERSION, ExpenseChangedV1.class);
  public static final EventType<ExpenseChangedV1> EXPENSE_DELETED =
      EventType.of("ledger", "expense", "deleted", ExpenseChangedV1.VERSION, ExpenseChangedV1.class);
  public static final EventType<ExpenseChangedV1> EXPENSE_RESTORED =
      EventType.of("ledger", "expense", "restored", ExpenseChangedV1.VERSION, ExpenseChangedV1.class);

  /** Topics ledger produces (created at startup by the relay, MASTER_PLAN 6.2). */
  public static final List<String> AGGREGATES = List.of("expense");

  private LedgerEvents() {}
}
