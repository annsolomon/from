package in.dexter.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.portfolio.config.StatementCloserJob;
import in.dexter.portfolio.support.PortfolioIT;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Card transactions (MASTER_PLAN P11): one write moves the statement of the cycle, earns on the card's catalog
 * terms (points ledger + monthly savings), and writes portfolio.transaction.recorded; the closer generates ended
 * cycles.
 */
class TransactionsIT extends PortfolioIT {
  static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Autowired
  StatementCloserJob closer;

  @Test
  void aPurchaseMovesItsStatementAndEarnsOnTheCatalogTerms() {
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, POINTS_CARD); // statement day 15
    Res r = record(a, card, txn("2026-09-10T08:30:00Z", 75_000, "purchase", "SWIGGY*88123004 BANGALORE",
        "food.delivery"));
    assertThat(r.status()).as(r.toString()).isEqualTo(201);
    JsonNode t = r.json();
    assertThat(t.get("direction").asString()).isEqualTo("debit");
    assertThat(t.get("reward").get("type").asString()).isEqualTo("points");
    assertThat(t.get("reward").get("points").asLong()).as("10 % of Rs 750 at 25 paise a point").isEqualTo(300);
    assertThat(t.get("reward").get("valuePaise").asLong()).isEqualTo(7_500);
    assertThat(record(a, card, txn("2026-09-12T08:30:00Z", 200_000, "purchase", "HP PETROL PUMP", "fuel.petrol"))
        .json().get("reward").get("type").asString()).as("fuel is excluded").isEqualTo("none");
    record(a, card, txn("2026-09-13T08:30:00Z", 50_000, "refund", "SWIGGY REFUND", "food.delivery"));
    record(a, card, txn("2026-09-14T08:30:00Z", 35_400, "fee", "ANNUAL FEE + GST", null));
    record(a, card, txn("2026-09-20T08:30:00Z", 10_000, "purchase", "CAFE", "food.cafe"));

    JsonNode statements = get("/v1/cards/" + card + "/statements", a).json().get("data");
    assertThat(statements.size()).as("Aug 16-Sep 15 and Sep 16-Oct 15").isEqualTo(2);
    JsonNode sep = statements.get(1);
    assertThat(sep.get("periodStart").asString()).isEqualTo("2026-08-16");
    assertThat(sep.get("periodEnd").asString()).isEqualTo("2026-09-15");
    assertThat(sep.get("dueDate").asString()).isEqualTo("2026-10-05");
    assertThat(sep.get("txnCount").asInt()).isEqualTo(4);
    assertThat(sep.get("debitsPaise").asLong()).isEqualTo(75_000 + 200_000 + 35_400);
    assertThat(sep.get("creditsPaise").asLong()).isEqualTo(50_000);
    assertThat(sep.get("totalDuePaise").asLong()).isEqualTo(75_000 + 200_000 + 35_400 - 50_000);

    JsonNode savings = JSON.readTree(ADMIN.sql("SELECT json_build_object('spend', spend_paise, 'n', txn_count,"
            + " 'rewards', rewards_value_paise, 'fees', fees_paise)::text FROM portfolio.card_savings_summary"
            + " WHERE user_card_id = :c AND period = DATE '2026-09-01'").param("c", card).query(String.class).single());
    assertThat(savings.get("spend").asLong()).isEqualTo(75_000 + 200_000 - 50_000 + 10_000);
    assertThat(savings.get("n").asInt()).isEqualTo(5);
    assertThat(savings.get("rewards").asLong()).as("7,500 + 1 % base (4 %) of Rs 100 = 400").isEqualTo(7_500 + 400);
    assertThat(savings.get("fees").asLong()).isEqualTo(35_400);
    assertThat(outboxRows(a, "portfolio.transaction.recorded")).isEqualTo(5);

    JsonNode list = get("/v1/cards/" + card + "/transactions?limit=2", a).json();
    assertThat(list.get("data").get(0).get("description").asString()).as("newest first").isEqualTo("CAFE");
    assertThat(list.get("page").get("hasMore").asBoolean()).isTrue();
    String id = t.get("id").asString();
    assertThat(get("/v1/cards/" + card + "/transactions/" + id, a).json().get("statementId").asString())
        .isEqualTo(sep.get("id").asString());
  }

  @Test
  void endedCyclesAreGenerated() {
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, CASHBACK_CARD);
    record(a, card, txn("2026-06-02T08:30:00Z", 1_000_000, "purchase", "CROMA", "shopping.electronics"));
    record(a, card, txn("2026-06-03T08:30:00Z", 20_000, "purchase", "CAFE", "food.cafe"));
    UUID other = newCard(a, POINTS_CARD);
    record(a, other, txn(Instant.now().minusSeconds(60).toString(), 5_000, "purchase", "TEA", "food.cafe"));
    closer.runOnce();
    JsonNode june = get("/v1/cards/" + card + "/statements", a).json().get("data").get(0);
    assertThat(june.get("status").asString()).isEqualTo("generated");
    assertThat(june.get("minDuePaise").asLong()).as("5 % of Rs 10,200").isEqualTo(51_000);
    JsonNode current = get("/v1/cards/" + other + "/statements", a).json().get("data").get(0);
    assertThat(LocalDate.parse(current.get("periodEnd").asString())).isAfterOrEqualTo(LocalDate.now(IST));
    assertThat(current.get("status").asString()).as("the open cycle stays open").isEqualTo("open");
  }

  @Test
  void refusals() {
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, POINTS_CARD);
    var body = txn("2026-09-10T08:30:00Z", 1_000, "purchase", "X", null);
    body.put("clientRef", "stmt-row-1");
    assertThat(record(a, card, body).status()).isEqualTo(201);
    assertThat(record(a, card, body).code()).isEqualTo("CLIENT_REF_TAKEN");
    assertThat(record(UUID.randomUUID(), card, txn("2026-09-10T08:30:00Z", 1_000, "purchase", "X", null)).code())
        .as("another tenant's card").isEqualTo("NOT_FOUND");
    Res bad = record(a, card, txn(Instant.now().plusSeconds(3_600).toString(), 0, "gift", "", "Bad.Path"));
    assertThat(bad.json().get("errors")).extracting(e -> e.get("field").asString())
        .contains("postedAt", "amountPaise", "kind", "description", "categoryPath");
    assertThat(patch("/v1/cards/" + card, a, "{\"status\":\"closed\"}", "\"0\"").status()).isEqualTo(200);
    assertThat(record(a, card, txn("2026-09-10T08:30:00Z", 1_000, "purchase", "X", null)).code())
        .isEqualTo("CARD_NOT_ACTIVE");
    assertThat(send("POST", "/v1/cards/" + card + "/transactions", "{}", "application/json", auth(a)).code())
        .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
  }
}
