package in.dexter.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.contracts.events.ledger.ExpenseChangedV1;
import in.dexter.events.EventEnvelope;
import in.dexter.portfolio.app.ProjectionService;
import in.dexter.portfolio.app.ProjectionService.Outcome;
import in.dexter.portfolio.support.Events;
import in.dexter.portfolio.support.PortfolioIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Auto-match (MASTER_PLAN P11): ledger.expense.* events build the expense projection (version-guarded, once per event
 * id) and suggest the card transaction that paid them — from either side; confirm links the two, reject drops the
 * suggestion; a deleted expense loses its open suggestions.
 */
class LinksIT extends PortfolioIT {
  @Autowired
  ProjectionService projections;

  List<JsonNode> suggestions(UUID account) {
    List<JsonNode> out = new java.util.ArrayList<>();
    get("/v1/links/suggestions?limit=100", account).json().get("data").forEach(out::add);
    return out;
  }

  @Test
  void anExpenseAfterItsTransactionIsSuggestedAndConfirmed() {
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, POINTS_CARD);
    String txnId = record(a, card, txn("2026-09-10T08:30:00Z", 64_900, "purchase", "SWIGGY*88123004", "food.delivery"))
        .json().get("id").asString();
    record(a, card, txn("2026-09-10T09:30:00Z", 64_900, "purchase", "ZOMATO GURGAON", "food.delivery")); // decoy
    UUID expense = UUID.randomUUID();
    EventEnvelope e = Events.created(a, expense, Events.state(64_900, "2026-09-11", card, "Swiggy"));
    assertThat(projections.onExpenseEvent(e)).isEqualTo(Outcome.APPLIED);
    assertThat(projections.onExpenseEvent(e)).as("same event id again").isEqualTo(Outcome.DUPLICATE);
    List<JsonNode> s = suggestions(a);
    assertThat(s).hasSize(1);
    JsonNode sug = s.getFirst();
    assertThat(sug.get("transactionId").asString()).isEqualTo(txnId);
    assertThat(sug.get("expenseId").asString()).isEqualTo(expense.toString());
    assertThat(sug.get("expenseMerchant").asString()).isEqualTo("Swiggy");
    assertThat(sug.get("score").asDouble()).isEqualTo(0.917);

    String id = sug.get("id").asString();
    Res ok = post("/v1/links/" + id + ":confirm", a, null);
    assertThat(ok.status()).as(ok.toString()).isEqualTo(200);
    assertThat(ok.json().get("status").asString()).isEqualTo("confirmed");
    assertThat(post("/v1/links/" + id + ":confirm", a, null).status()).as("idempotent").isEqualTo(200);
    assertThat(post("/v1/links/" + id + ":reject", a, null).code()).isEqualTo("LINK_DECIDED");
    assertThat(get("/v1/cards/" + card + "/transactions/" + txnId, a).json().get("expenseId").asString())
        .isEqualTo(expense.toString());
    assertThat(outboxRows(a, "portfolio.link.confirmed")).isEqualTo(1);
    assertThat(suggestions(a)).isEmpty();
    assertThat(post("/v1/links/" + id + ":confirm", UUID.randomUUID(), null).code()).isEqualTo("NOT_FOUND");
  }

  @Test
  void aTransactionAfterItsExpenseIsSuggestedAndRejectable() {
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, POINTS_CARD);
    UUID expense = UUID.randomUUID();
    projections.onExpenseEvent(Events.created(a, expense, Events.state(129_900, "2026-09-20", card, "Amazon India")));
    record(a, card, txn("2026-09-22T05:00:00Z", 129_900, "purchase", "AMAZON PAY INDIA PVT LTD", "shopping.online"));
    List<JsonNode> s = suggestions(a);
    assertThat(s).hasSize(1);
    String id = s.getFirst().get("id").asString();
    assertThat(post("/v1/links/" + id + ":reject", a, null).json().get("status").asString()).isEqualTo("rejected");
    assertThat(post("/v1/links/" + id + ":confirm", a, null).code()).isEqualTo("LINK_DECIDED");
    assertThat(suggestions(a)).isEmpty();
    // too far apart, other amount, other card: nothing
    projections.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(5_000, "2026-09-01", card, "Uber")));
    record(a, card, txn("2026-09-05T05:00:00Z", 5_000, "purchase", "UBER TRIP", "travel.cab"));
    projections.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(7_000, "2026-09-06", card, "Ola")));
    record(a, card, txn("2026-09-06T05:00:00Z", 7_001, "purchase", "OLA CABS", "travel.cab"));
    projections.onExpenseEvent(Events.created(a, UUID.randomUUID(), Events.state(9_000, "2026-09-07", null, "Ola")));
    record(a, card, txn("2026-09-07T05:00:00Z", 9_000, "purchase", "OLA CABS", "travel.cab"));
    assertThat(suggestions(a)).isEmpty();
  }

  @Test
  void expenseChangesKeepTheProjectionAndSuggestionsHonest() {
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, CASHBACK_CARD);
    record(a, card, txn("2026-09-15T05:00:00Z", 45_000, "purchase", "BIGBASKET.COM", "food.groceries"));
    UUID expense = UUID.randomUUID();
    var v0 = Events.state(45_000, "2026-09-15", card, "Big Basket");
    projections.onExpenseEvent(Events.created(a, expense, v0));
    assertThat(suggestions(a)).hasSize(1);
    var v2 = Events.state(46_000, "2026-09-15", card, "Big Basket");
    assertThat(projections.onExpenseEvent(Events.expense(ExpenseChangedV1.UPDATED, a, expense, 2, v0, v2)))
        .isEqualTo(Outcome.APPLIED);
    assertThat(suggestions(a)).as("the amount changed: the old suggestion is gone").isEmpty();
    assertThat(projections.onExpenseEvent(Events.expense(ExpenseChangedV1.UPDATED, a, expense, 1, v0, v0)))
        .as("an older version arriving late").isEqualTo(Outcome.STALE);
    assertThat(ADMIN.sql("SELECT amount_paise FROM portfolio.ledger_expenses_proj WHERE expense_id = :e")
        .param("e", expense).query(Long.class).single()).isEqualTo(46_000);
    var v3 = Events.state(45_000, "2026-09-15", card, "Big Basket");
    projections.onExpenseEvent(Events.expense(ExpenseChangedV1.UPDATED, a, expense, 3, v2, v3));
    assertThat(suggestions(a)).as("back to the transaction's amount: suggested again? the pair was rejected")
        .isEmpty();
    projections.onExpenseEvent(Events.expense(ExpenseChangedV1.DELETED, a, expense, 4, v3, null));
    assertThat(ADMIN.sql("SELECT deleted FROM portfolio.ledger_expenses_proj WHERE expense_id = :e")
        .param("e", expense).query(Boolean.class).single()).isTrue();
  }
}
