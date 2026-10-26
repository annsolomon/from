package in.dexter.portfolio.support;

import in.dexter.contracts.events.aiparser.StatementParsedV1;
import in.dexter.contracts.events.catalog.CardPublishedV1;
import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.contracts.events.ledger.ExpenseStateV1;
import in.dexter.events.EventEnvelope;
import java.time.Instant;
import java.util.List;
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

  /** The ai-parser's result for an upload (status parsed): lines in order, header for the card ending last4. */
  public static EventEnvelope parsed(UUID account, UUID upload, UUID card, String last4,
      List<StatementParsedV1.Line> lines) {
    return parsedEvent(account, new StatementParsedV1(upload, card, "parsed", null, null, "rules-1", "saffron-v1",
        "MOCK_SAFFRON", last4, "2026-09-06", "2026-10-05", "2026-10-25", 1_000_000L, 50_000L, 1, lines,
        Instant.now().toString()));
  }

  public static EventEnvelope parseFailed(UUID account, UUID upload, UUID card, String code) {
    return parsedEvent(account, new StatementParsedV1(upload, card, "failed", code, "could not read it", "rules-1",
        null, null, null, null, null, null, null, null, 0, List.of(), Instant.now().toString()));
  }

  public static EventEnvelope parsedEvent(UUID account, StatementParsedV1 data) {
    return new EventEnvelope(UUID.randomUUID(), StatementParsedV1.TYPE, 1, "ai-parser", Instant.now(), account,
        data.uploadId(), 1, null, PortfolioIT.JSON.valueToTree(data));
  }

  public static StatementParsedV1.Line line(int no, String day, String description, long paise, String kind) {
    String direction = List.of("purchase", "fee", "interest", "emi").contains(kind) ? "debit" : "credit";
    return new StatementParsedV1.Line(no, day, description, paise, direction, kind);
  }
}
