package in.dexter.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.portfolio.app.ProjectionService;
import in.dexter.portfolio.support.Events;
import in.dexter.portfolio.support.PortfolioIT;
import in.dexter.portfolio.support.SeedMerchants;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MASTER_PLAN P11 "auto-match accuracy on the seed": 400 card expenses over the seed's reference merchants, each
 * paid by a card transaction with a seed-shaped bank descriptor posted 0-2 days later or 1 day earlier (85 %), or
 * never charged to the card (15 %); a fifth of them also have a decoy: same card, same amount, within the window,
 * another merchant; plus 60 unrelated transactions. Half the expenses arrive before their transaction (matched from
 * the transaction side), half after (matched from the expense side). Precision ≥ 0.97, recall ≥ 0.95.
 */
class AutoMatchAccuracyIT extends PortfolioIT {
  private static final Logger LOG = LoggerFactory.getLogger(AutoMatchAccuracyIT.class);
  @Autowired
  ProjectionService projections;

  record Exp(UUID id, long paise, LocalDate day, String merchant, String txnId) {}

  @Test
  void suggestionsArePreciseAndComplete() {
    Random x = new Random(42);
    List<String> merchants = SeedMerchants.names();
    UUID a = UUID.randomUUID();
    UUID card = newCard(a, POINTS_CARD);
    LocalDate start = LocalDate.parse("2026-06-01");
    List<Exp> expenses = new ArrayList<>();
    Map<String, UUID> truth = new HashMap<>(); // txn id -> expense id
    int decoys = 0;
    for (int i = 0; i < 400; i++) {
      UUID id = UUID.randomUUID();
      long paise = 5_000 + x.nextInt(2_000_000);
      LocalDate day = start.plusDays(x.nextInt(100));
      String merchant = merchants.get(x.nextInt(merchants.size()));
      boolean charged = x.nextInt(100) < 85;
      boolean before = i % 2 == 0;
      if (before) {
        projections.onExpenseEvent(Events.created(a, id, Events.state(paise, day.toString(), card, merchant)));
      }
      String txnId = null;
      if (charged) {
        int shift = List.of(0, 0, 0, 1, 1, 2, -1).get(x.nextInt(7));
        txnId = post(card, paise, day.plusDays(shift), SeedMerchants.cardDescriptor(merchant, x));
        truth.put(txnId, id);
      }
      if (x.nextInt(100) < 20) {
        String other;
        do {
          other = merchants.get(x.nextInt(merchants.size()));
        } while (sameBrand(other, merchant));
        post(card, paise, day.plusDays(x.nextInt(3) - 1), SeedMerchants.cardDescriptor(other, x));
        decoys++;
      }
      if (!before) {
        projections.onExpenseEvent(Events.created(a, id, Events.state(paise, day.toString(), card, merchant)));
      }
      expenses.add(new Exp(id, paise, day, merchant, txnId));
    }
    for (int i = 0; i < 60; i++) {
      post(card, 5_000 + x.nextInt(2_000_000), start.plusDays(x.nextInt(100)),
          SeedMerchants.cardDescriptor(merchants.get(x.nextInt(merchants.size())), x));
    }

    List<Map<String, Object>> rows = ADMIN.sql("SELECT expense_id::text AS e, card_txn_id::text AS t FROM"
        + " portfolio.expense_links WHERE account_id = :a AND status = 'suggested'").param("a", a).query().listOfRows();
    int correct = 0;
    for (Map<String, Object> r : rows) {
      UUID expected = truth.get((String) r.get("t"));
      if (expected != null && expected.toString().equals(r.get("e"))) {
        correct++;
      }
    }
    long charged = expenses.stream().filter(e -> e.txnId() != null).count();
    double precision = rows.isEmpty() ? 0 : (double) correct / rows.size();
    double recall = (double) correct / charged;
    LOG.info("AUTO_MATCH expenses=400 charged={} decoys={} suggestions={} correct={} precision={} recall={}",
        charged, decoys, rows.size(), correct, String.format("%.3f", precision), String.format("%.3f", recall));
    assertThat(precision).as("precision").isGreaterThanOrEqualTo(0.97);
    assertThat(recall).as("recall").isGreaterThanOrEqualTo(0.95);
  }

  /** Decoys must be another merchant (not "Amazon" vs "Amazon Fresh", which a person could not tell apart either). */
  static boolean sameBrand(String a, String b) {
    String x = a.toLowerCase(java.util.Locale.ROOT).split(" ")[0];
    String y = b.toLowerCase(java.util.Locale.ROOT).split(" ")[0];
    return x.equals(y);
  }

  String post(UUID card, long paise, LocalDate day, String descriptor) {
    UUID account = owner(card);
    Res r = record(account, card, txn(day.atTime(9, 0).toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toString(), paise,
        "purchase", descriptor, null));
    if (r.status() != 201) {
      throw new AssertionError(r.toString());
    }
    return r.json().get("id").asString();
  }

  final Map<UUID, UUID> owners = new HashMap<>();

  UUID owner(UUID card) {
    return owners.computeIfAbsent(card, c -> ADMIN.sql("SELECT account_id FROM portfolio.user_cards WHERE id = :c")
        .param("c", c).query(UUID.class).single());
  }
}
