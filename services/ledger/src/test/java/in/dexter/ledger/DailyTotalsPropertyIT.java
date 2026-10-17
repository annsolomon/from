package in.dexter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.core.tenant.Tenant;
import in.dexter.ledger.app.ExpensePatch;
import in.dexter.ledger.app.ExpenseService;
import in.dexter.ledger.app.ExpenseView;
import in.dexter.ledger.app.NewExpense;
import in.dexter.ledger.support.LedgerIT;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MASTER_PLAN P07 property: after 1,000 random writes (create, update moving amount/day/category, delete, restore)
 * on three accounts, {@code expense_daily_totals} equals {@code SUM/COUNT} over the live expenses, row for row.
 */
class DailyTotalsPropertyIT extends LedgerIT {
  static final List<String> CATEGORIES = List.of("food.dining", "food.groceries", "travel.cab", "travel.air", "fuel",
      "general", "shopping.online");
  @Autowired
  ExpenseService service;

  @Test
  void dailyTotalsAlwaysEqualTheSumOverExpenses() {
    Random rnd = new Random(20261017);
    List<UUID> accounts = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    Map<UUID, List<UUID>> ids = Map.of(accounts.get(0), new ArrayList<>(), accounts.get(1), new ArrayList<>(),
        accounts.get(2), new ArrayList<>());
    for (int op = 0; op < 1000; op++) {
      UUID a = accounts.get(rnd.nextInt(accounts.size()));
      Tenant t = Tenant.user(a);
      List<UUID> mine = ids.get(a);
      int kind = mine.isEmpty() ? 0 : rnd.nextInt(10);
      if (kind < 4) {
        ExpenseView v = JSON.readValue(service.create(t, UUID.randomUUID(), new NewExpense(when(rnd),
            1L + rnd.nextInt(500_000), "M" + rnd.nextInt(20), pick(rnd), "upi", null, null, null, null)).body(),
            ExpenseView.class);
        mine.add(v.id());
      } else if (kind < 8) {
        UUID id = mine.get(rnd.nextInt(mine.size()));
        try {
          ExpenseView cur = service.get(t, id);
          service.patch(t, id, cur.version(), new ExpensePatch(
              rnd.nextBoolean() ? ExpensePatch.Field.of(when(rnd)) : ExpensePatch.Field.absent(),
              rnd.nextBoolean() ? ExpensePatch.Field.of(1L + rnd.nextInt(500_000)) : ExpensePatch.Field.absent(),
              ExpensePatch.Field.absent(),
              rnd.nextBoolean() ? ExpensePatch.Field.of(pick(rnd)) : ExpensePatch.Field.absent(),
              ExpensePatch.Field.absent(), ExpensePatch.Field.absent(), ExpensePatch.Field.absent(),
              ExpensePatch.Field.absent()));
        } catch (in.dexter.core.error.NotFoundException deleted) {
          // updating a deleted expense is a 404; fine for the property
        }
      } else if (kind == 8) {
        service.delete(t, mine.get(rnd.nextInt(mine.size())));
      } else {
        service.restore(t, mine.get(rnd.nextInt(mine.size())));
      }
    }
    for (UUID a : accounts) {
      List<String> expected = ADMIN.sql("""
          SELECT d || '|' || r || '|' || sum(amount_paise) || '|' || count(*)
          FROM (SELECT (occurred_at AT TIME ZONE 'Asia/Kolkata')::date AS d, subpath(category_path, 0, 1)::text AS r,
                       amount_paise
                FROM ledger.expenses WHERE account_id = :a AND deleted_at IS NULL) x
          GROUP BY d, r ORDER BY 1
          """).param("a", a).query(String.class).list();
      List<String> actual = ADMIN.sql("""
          SELECT day || '|' || category_root::text || '|' || total_paise || '|' || txn_count
          FROM ledger.expense_daily_totals WHERE account_id = :a ORDER BY 1
          """).param("a", a).query(String.class).list();
      assertThat(actual).as("account " + a).isEqualTo(expected);
      assertThat(actual).isNotEmpty();
    }
  }

  static Instant when(Random rnd) {
    return Instant.parse("2026-09-01T00:00:00Z").plusSeconds(rnd.nextInt(30 * 86_400));
  }

  static String pick(Random rnd) {
    return CATEGORIES.get(rnd.nextInt(CATEGORIES.size()));
  }
}
