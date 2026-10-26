package in.dexter.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.portfolio.support.PortfolioIT;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.rls.RlsProofKit;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** RLS proof (MASTER_PLAN 10, P11 "RLS proof on all tables") on every portfolio table. */
class PortfolioRlsIT extends PortfolioIT {
  static final ServiceDb DBX = TestPostgres.flywayDb("portfolio");
  /** A user card of the tenant parameter, for child rows. */
  static final String CARD = "WITH c AS (INSERT INTO portfolio.user_cards (id, account_id, card_product_id, nickname,"
      + " last4, network, statement_day) VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'x', '1234', 'visa', 5)"
      + " RETURNING id, account_id)";

  static RlsProofKit kit(String table, String insert, String move) {
    return new RlsProofKit(DBX, table, insert, move);
  }

  @Test
  void userCards() throws Exception {
    kit("user_cards", "INSERT INTO portfolio.user_cards (id, account_id, card_product_id, nickname, last4, network,"
        + " statement_day) VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'x', '1234', 'visa', 5)",
        "UPDATE portfolio.user_cards SET account_id = ?").proveAll();
  }

  @Test
  void statements() throws Exception {
    kit("statements", CARD + " INSERT INTO portfolio.statements (id, account_id, user_card_id, period_start, period_end,"
        + " due_date) SELECT gen_random_uuid(), account_id, id, DATE '2026-09-06', DATE '2026-10-05',"
        + " DATE '2026-10-25' FROM c", "UPDATE portfolio.statements SET account_id = ?").proveAll();
  }

  @Test
  void cardTransactions() throws Exception {
    kit("card_transactions", CARD + " INSERT INTO portfolio.card_transactions (id, account_id, user_card_id, posted_at,"
        + " amount_paise, direction, description) SELECT gen_random_uuid(), account_id, id, now(), 100, 'debit', 'x'"
        + " FROM c", "UPDATE portfolio.card_transactions SET account_id = ?").proveAll();
  }

  @Test
  void expenseLinks() throws Exception {
    kit("expense_links", CARD + ", t AS (INSERT INTO portfolio.card_transactions (id, account_id, user_card_id,"
        + " posted_at, amount_paise, direction, description) SELECT gen_random_uuid(), account_id, id, now(), 100,"
        + " 'debit', 'x' FROM c RETURNING id, account_id) INSERT INTO portfolio.expense_links (id, account_id,"
        + " expense_id, card_txn_id, status, score) SELECT gen_random_uuid(), account_id, gen_random_uuid(), id,"
        + " 'suggested', 0.9 FROM t", "UPDATE portfolio.expense_links SET account_id = ?").proveAll();
  }

  @Test
  void ledgerExpenseProjection() throws Exception {
    kit("ledger_expenses_proj", "INSERT INTO portfolio.ledger_expenses_proj (expense_id, account_id, amount_paise, day,"
        + " projected_version) VALUES (gen_random_uuid(), ?, 100, current_date, 0)",
        "UPDATE portfolio.ledger_expenses_proj SET account_id = ?").proveAll();
  }

  @Test
  void emiPlansAndSchedule() throws Exception {
    String plan = CARD + ", p AS (INSERT INTO portfolio.emi_plans (id, account_id, user_card_id, description,"
        + " principal_paise, annual_rate_bps, tenure_months, first_due_date, emi_paise, total_interest_paise,"
        + " total_gst_paise) SELECT gen_random_uuid(), account_id, id, 'x', 100000, 0, 1, current_date, 100000, 0, 0"
        + " FROM c RETURNING id, account_id)";
    kit("emi_plans", CARD + " INSERT INTO portfolio.emi_plans (id, account_id, user_card_id, description,"
        + " principal_paise, annual_rate_bps, tenure_months, first_due_date, emi_paise, total_interest_paise,"
        + " total_gst_paise) SELECT gen_random_uuid(), account_id, id, 'x', 100000, 0, 1, current_date, 100000, 0, 0"
        + " FROM c", "UPDATE portfolio.emi_plans SET account_id = ?").proveAll();
    kit("emi_schedule", plan + " INSERT INTO portfolio.emi_schedule (plan_id, account_id, installment_no, due_date,"
        + " principal_paise, interest_paise, gst_paise, total_paise, outstanding_after_paise) SELECT id, account_id,"
        + " 1, current_date, 100000, 0, 0, 100000, 0 FROM p", "UPDATE portfolio.emi_schedule SET account_id = ?")
        .proveAll();
  }

  @Test
  void rewardPointsLedgerIsAppendOnly() throws Exception {
    kit("reward_points_ledger", CARD + " INSERT INTO portfolio.reward_points_ledger (id, account_id, user_card_id,"
        + " kind, points) SELECT gen_random_uuid(), account_id, id, 'earn', 10 FROM c", null).proveAll();
    try (Connection c = DBX.conn("app"); Statement s = c.createStatement()) {
      assertThatThrownBy(() -> s.executeUpdate("UPDATE portfolio.reward_points_ledger SET points = 1"))
          .hasMessageContaining("permission denied");
      assertThatThrownBy(() -> s.executeUpdate("DELETE FROM portfolio.reward_points_ledger"))
          .hasMessageContaining("permission denied");
    }
  }

  @Test
  void cardSavingsSummary() throws Exception {
    kit("card_savings_summary", CARD + " INSERT INTO portfolio.card_savings_summary (account_id, user_card_id, period)"
        + " SELECT account_id, id, DATE '2026-09-01' FROM c", "UPDATE portfolio.card_savings_summary SET account_id = ?")
        .proveAll();
  }

  /** P12: uploads, the parser's headers and the staged lines. */
  @Test
  void statementUploadsParsedHeadersAndLines() throws Exception {
    String upload = CARD + ", u AS (INSERT INTO portfolio.statement_uploads (id, account_id, user_card_id, file_name,"
        + " quarantine_key) SELECT gen_random_uuid(), account_id, id, 'x.pdf', 'q' FROM c RETURNING id, account_id)";
    kit("statement_uploads", CARD + " INSERT INTO portfolio.statement_uploads (id, account_id, user_card_id,"
        + " file_name, quarantine_key) SELECT gen_random_uuid(), account_id, id, 'x.pdf', 'q' FROM c",
        "UPDATE portfolio.statement_uploads SET account_id = ?").proveAll();
    kit("parsed_statements", upload + " INSERT INTO portfolio.parsed_statements (upload_id, account_id,"
        + " parser_version, line_count, debits_paise, credits_paise, parsed_at) SELECT id, account_id, 'rules-1', 1,"
        + " 100, 0, now() FROM u", "UPDATE portfolio.parsed_statements SET account_id = ?").proveAll();
    kit("parsed_lines", upload + " INSERT INTO portfolio.parsed_lines (upload_id, line_no, account_id, posted_on,"
        + " description, amount_paise, direction, kind, client_ref) SELECT id, 1, account_id, current_date, 'x', 100,"
        + " 'debit', 'purchase', 'stmt:x' FROM u", "UPDATE portfolio.parsed_lines SET account_id = ?").proveAll();
  }

  /** The catalog projection is global: every tenant reads it, only the PROJECTOR actor writes it. */
  @Test
  void catalogProjectionIsReadOnlyForTenants() throws Exception {
    try (Connection c = DBX.conn("app")) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("SELECT set_config('app.account_id', gen_random_uuid()::text, true),"
            + " set_config('app.actor_role', 'USER', true)");
        try (ResultSet rs = s.executeQuery("SELECT count(1) FROM portfolio.catalog_products_proj")) {
          rs.next();
          assertThat(rs.getLong(1)).as("tenants read the catalog").isPositive();
        }
        assertThat(s.executeUpdate("UPDATE portfolio.catalog_products_proj SET annual_fee_paise = 0")).isZero();
        assertThatThrownBy(() -> s.executeUpdate("INSERT INTO portfolio.catalog_products_proj (card_product_id, slug,"
            + " name, issuer_code, network, tier, status, current_version, snapshot, projected_version) VALUES"
            + " (gen_random_uuid(), 's', 'n', 'i', 'visa', 't', 'published', 1, '{}', 1)"))
            .hasMessageContaining("row-level security");
      } finally {
        c.rollback();
      }
      try (Statement s = c.createStatement()) {
        try (ResultSet rs = s.executeQuery("SELECT count(1) FROM portfolio.catalog_products_proj")) {
          rs.next();
          assertThat(rs.getLong(1)).as("no context, nothing").isZero();
        }
      } finally {
        c.rollback();
      }
    }
  }
}
