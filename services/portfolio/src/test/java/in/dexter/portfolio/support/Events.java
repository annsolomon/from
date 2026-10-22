package in.dexter.portfolio.support;

import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.contracts.events.ledger.ExpenseStateV1;
import in.dexter.events.EventEnvelope;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Envelopes as ledger and catalog publish them (MASTER_PLAN 6.1), for calling the consumers' handlers directly. */
public final class Events {
  private Events() {}

  public static ExpenseStateV1 state(long paise, String day, UUID card, String merchant) {
    return new ExpenseStateV1(paise, day, "general", card == null ? "upi" : "card", card, null, merchant,
        day + "T06:00:00Z");
  }

  public static EventEnvelope expense(String type, UUID account, UUID expenseId, long version, ExpenseStateV1 before,
      ExpenseStateV1 after) {
    return new EventEnvelope(UUID.randomUUID(), type, 1, "ledger", Instant.now(), account, expenseId, version, null,
        PortfolioIT.JSON.valueToTree(new ExpenseChangedV1(expenseId, version, before, after, Instant.now().toString())));
  }

  public static EventEnvelope created(UUID account, UUID expenseId, ExpenseStateV1 after) {
    return expense(ExpenseChangedV1.CREATED, account, expenseId, 0, null, after);
  }

  public static EventEnvelope card(UUID cardId, String slug, String status, int version, long aggregateVersion,
      Map<String, Object> snapshot) {
    return new EventEnvelope(UUID.randomUUID(), "retired".equals(status) ? CardPublishedV1.RETIRED
        : CardPublishedV1.PUBLISHED, 1, "catalog", Instant.now(), null, cardId, aggregateVersion, null,
        PortfolioIT.JSON.valueToTree(new CardPublishedV1(cardId, slug, status, version, Instant.now().toString(), null,
            snapshot)));
  }
}
