package in.dexter.contracts.events.ledger;

import java.util.UUID;

/**
 * {@code ledger.expense.created|updated|deleted|restored} v1 (one payload shape for all four, MASTER_PLAN P07):
 * {@code before} is null on created/restored, {@code after} is null on deleted. {@code version} is the expense's
 * version after the change (also the envelope's aggregateVersion). Envelope accountId = the expense owner.
 */
public record ExpenseChangedV1(UUID expenseId, long version, ExpenseStateV1 before, ExpenseStateV1 after,
    String changedAt) {
  public static final String CREATED = "ledger.expense.created";
  public static final String UPDATED = "ledger.expense.updated";
  public static final String DELETED = "ledger.expense.deleted";
  public static final String RESTORED = "ledger.expense.restored";
  public static final int VERSION = 1;
  public static final String TOPIC = "dexter.ledger.expense.v1";
}
