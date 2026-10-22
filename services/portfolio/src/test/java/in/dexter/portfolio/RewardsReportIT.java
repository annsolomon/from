package in.dexter.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.portfolio.support.PortfolioIT;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Rewards balance + ledger (FIFO expiry, expiring soon), redeem/adjust, and the portfolio report (MASTER_PLAN P11). */
class RewardsReportIT extends PortfolioIT {

  static Map<String, Object> entry(UUID card, String kind, long points, String note) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("userCardId", card);
    m.put("kind", kind);
    m.put("points", points);
    m.put("note", note);
    return m;
  }

  @Test
  void pointsBalanceLedgerAndRedemption() {
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, POINTS_CARD);
    record(a, card, txn("2026-09-01T10:00:00Z", 100_000, "purchase", "SWIGGY", "food.delivery")); // 400 points
    record(a, card, txn("2026-09-02T10:00:00Z", 50_000, "purchase", "CROMA", "shopping")); // 4 % = 80 points
    // a lot about to expire (written as the seed or a statement import would)
    ADMIN.sql("INSERT INTO portfolio.reward_points_ledger (id, account_id, user_card_id, kind, points,"
            + " value_milli_paise, expires_on, source_ref) VALUES (gen_random_uuid(), :a, :c, 'earn', 120, 3000000,"
            + " :exp, 'import:old-lot')").param("a", a).param("c", card)
        .param("exp", LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(20)).update();

    JsonNode b = get("/v1/rewards/balance", a).json();
    assertThat(b.get("points").asLong()).isEqualTo(600);
    assertThat(b.get("valuePaise").asLong()).as("600 x 25 paise").isEqualTo(15_000);
    assertThat(b.get("expiringSoon").asLong()).isEqualTo(120);
    assertThat(b.get("cards").get(0).get("expiring").get(0).get("points").asLong()).isEqualTo(120);

    assertThat(post("/v1/rewards/ledger", a, entry(card, "redeem", 1_000, null)).code())
        .isEqualTo("INSUFFICIENT_POINTS");
    Res r = post("/v1/rewards/ledger", a, entry(card, "redeem", 100, "Amazon voucher"));
    assertThat(r.status()).as(r.toString()).isEqualTo(201);
    assertThat(r.json().get("points").asLong()).isEqualTo(-100);
    JsonNode after = get("/v1/rewards/balance", a).json();
    assertThat(after.get("points").asLong()).isEqualTo(500);
    assertThat(after.get("expiringSoon").asLong()).as("redemptions use the oldest expiry first").isEqualTo(20);
    assertThat(post("/v1/rewards/ledger", a, entry(card, "adjust", -5, null)).json().get("errors").get(0)
        .get("field").asString()).as("an adjustment needs a note").isEqualTo("note");

    JsonNode ledger = get("/v1/rewards/ledger?cardId=" + card + "&limit=10", a).json();
    assertThat(ledger.get("data").size()).isEqualTo(4);
    assertThat(ledger.get("data").get(0).get("kind").asString()).isEqualTo("redeem");
    assertThat(ledger.get("data")).filteredOn(e -> e.get("expiringSoon").asBoolean()).hasSize(1);
    assertThat(get("/v1/rewards/balance", UUID.randomUUID()).json().get("points").asLong()).isZero();
  }

  @Test
  void theReportNetsRewardsAgainstFees() {
    UUID a = UUID.randomUUID();
    UUID points = newCard(a, POINTS_CARD); // annual fee Rs 500
    UUID cash = newCard(a, CASHBACK_CARD); // lifetime free
    record(a, points, txn("2026-08-05T10:00:00Z", 100_000, "purchase", "SWIGGY", "food.delivery")); // Rs 100 value
    record(a, points, txn("2026-08-06T10:00:00Z", 35_400, "fee", "LATE FEE", null));
    record(a, cash, txn("2026-08-07T10:00:00Z", 200_000, "purchase", "AMAZON", "shopping.online")); // 5 % = Rs 100
    record(a, cash, txn("2026-07-07T10:00:00Z", 100_000, "purchase", "AMAZON", "shopping.online"));

    JsonNode m = get("/v1/portfolio/report?period=month&month=2026-08", a).json();
    assertThat(m.get("from").asString()).isEqualTo("2026-08-01");
    assertThat(m.get("spendPaise").asLong()).isEqualTo(300_000);
    assertThat(m.get("rewardsValuePaise").asLong()).isEqualTo(20_000);
    assertThat(m.get("feesPaise").asLong()).isEqualTo(35_400);
    assertThat(m.get("annualFeePaise").asLong()).as("1/12 of Rs 500").isEqualTo(4_166);
    assertThat(m.get("netPaise").asLong()).isEqualTo(20_000 - 35_400 - 4_166);
    assertThat(m.get("cards").size()).isEqualTo(2);

    JsonNode y = get("/v1/portfolio/report?period=year&year=2026", a).json();
    assertThat(y.get("spendPaise").asLong()).isEqualTo(400_000);
    assertThat(y.get("annualFeePaise").asLong()).isEqualTo(50_000);
    assertThat(get("/v1/portfolio/report?period=week", a).code()).isEqualTo("VALIDATION_FAILED");
    assertThat(get("/v1/portfolio/report?period=month&month=2026-13", a).code()).isEqualTo("VALIDATION_FAILED");
  }
}
