package in.dexter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import in.dexter.ledger.support.LedgerIT;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.rls.RlsProofKit;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** RLS proof (MASTER_PLAN 10) on every ledger tenant table. */
class LedgerRlsIT extends LedgerIT {

  @Test
  void expenses() throws Exception {
    new RlsProofKit(TestPostgres.flywayDb("ledger"), "expenses",
        "INSERT INTO ledger.expenses (id, account_id, occurred_at, amount_paise, payment_method)"
            + " VALUES (gen_random_uuid(), ?, now(), 100, 'cash')",
        "UPDATE ledger.expenses SET account_id = ?").proveAll();
  }

  @Test
  void dailyTotals() throws Exception {
    new RlsProofKit(TestPostgres.flywayDb("ledger"), "expense_daily_totals",
        "INSERT INTO ledger.expense_daily_totals (account_id, day, category_root, total_paise, txn_count)"
            + " VALUES (?, current_date, 'food', 100, 1)",
        "UPDATE ledger.expense_daily_totals SET account_id = ?").proveAll();
  }

  @Test
  void categorizationRules() throws Exception {
    new RlsProofKit(TestPostgres.flywayDb("ledger"), "categorization_rules",
        "INSERT INTO ledger.categorization_rules (id, account_id, match_type, pattern, category_path)"
            + " VALUES (gen_random_uuid(), ?, 'contains', 'swiggy', 'food.delivery')",
        "UPDATE ledger.categorization_rules SET account_id = ?").proveAll();
  }

  /** Custom categories are private; the system tree is shared but never writable, and invisible without a tenant. */
  @Test
  void categories() throws Exception {
    new RlsProofKit(TestPostgres.flywayDb("ledger"), "categories",
        "INSERT INTO ledger.categories (id, account_id, path, name, is_system)"
            + " VALUES (gen_random_uuid(), ?, CAST('food.rls_' || substr(md5(random()::text), 1, 8) AS ltree), 'x', false)",
        null).proveAll();
    var db = TestPostgres.flywayDb("ledger");
    try (Connection c = db.conn("app")) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("SELECT set_config('app.account_id', gen_random_uuid()::text, true)");
        try (ResultSet rs = s.executeQuery("SELECT count(1) FROM ledger.categories WHERE is_system")) {
          rs.next();
          assertThat(rs.getLong(1)).as("system tree visible to a tenant").isGreaterThanOrEqualTo(48);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.execute(
                "INSERT INTO ledger.categories (id, account_id, path, name, is_system)"
                    + " VALUES (gen_random_uuid(), NULL, 'hacked', 'x', true)"))
            .hasMessageContaining("row-level security");
      } finally {
        c.rollback();
      }
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.exec("app",
            "UPDATE ledger.categories SET name = 'x'"))
        .hasMessageContaining("permission denied");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.exec("app", "DELETE FROM ledger.expenses"))
        .as("expenses are soft-deleted only").hasMessageContaining("permission denied");
  }
}
