package in.dexter.planning.support;

import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.contracts.events.ledger.ExpenseStateV1;
import in.dexter.events.EventEnvelope;
import java.time.Instant;
import java.util.UUID;

/**
 * Envelopes as ledger publishes them (MASTER_PLAN 6.1), for calling planning's consumer handler directly.
 *
 * <p>{@code day} is the expense's Asia/Kolkata day, which is what the ledger stamps — so a test can state
 * "23:30 IST on the 31st" as the day it arrives as.
 */
public final class Events {
  private Events() {}

  public static ExpenseStateV1 state(long paise, String day, String categoryPath) {
    return new ExpenseStateV1(paise, day, categoryPath, "upi", null, null, "Test merchant", day + "T06:00:00Z");
  }

  public static EventEnvelope expense(String type, UUID account, UUID expenseId, long version,
      ExpenseStateV1 before, ExpenseStateV1 after) {
    return new EventEnvelope(UUID.randomUUID(), type, 1, "ledger", Instant.now(), account, expenseId, version, null,
        PlanningIT.JSON.valueToTree(new ExpenseChangedV1(expenseId, version, before, after,
            Instant.now().toString())));
  }

  public static EventEnvelope created(UUID account, UUID expenseId, ExpenseStateV1 after) {
    return expense(ExpenseChangedV1.CREATED, account, expenseId, 0, null, after);
  }

  public static EventEnvelope updated(UUID account, UUID expenseId, long version, ExpenseStateV1 before,
      ExpenseStateV1 after) {
    return expense(ExpenseChangedV1.UPDATED, account, expenseId, version, before, after);
  }

  public static EventEnvelope deleted(UUID account, UUID expenseId, long version, ExpenseStateV1 before) {
    return expense(ExpenseChangedV1.DELETED, account, expenseId, version, before, null);
  }

  public static EventEnvelope restored(UUID account, UUID expenseId, long version, ExpenseStateV1 after) {
    return expense(ExpenseChangedV1.RESTORED, account, expenseId, version, null, after);
  }
}
