package in.dexter.planning;

import in.dexter.planning.support.PlanningIT;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.rls.RlsProofKit;
import org.junit.jupiter.api.Test;

/** RLS proof (MASTER_PLAN 10, P13 DoD) on every planning table: flags, isolation, fail-closed, WITH CHECK. */
class PlanningRlsIT extends PlanningIT {
  static final ServiceDb DBX = TestPostgres.flywayDb("planning");

  /** A budget of the tenant parameter, for the child rows that reference one. */
  static final String BUDGET = "WITH b AS (INSERT INTO planning.budgets (id, account_id, name, period, limit_paise,"
      + " starts_on) VALUES (gen_random_uuid(), ?, 'x', 'monthly', 100000, DATE '2026-01-01')"
      + " RETURNING id, account_id)";

  /** A liability of the tenant parameter, for its schedule rows. */
  static final String LIABILITY = "WITH l AS (INSERT INTO planning.liabilities (id, account_id, kind, lender,"
      + " principal_paise, apr_bps, tenure_months, start_date, emi_paise) VALUES (gen_random_uuid(), ?, 'loan',"
      + " 'x', 1000000, 1200, 12, DATE '2026-01-01', 88849) RETURNING id, account_id)";

  static RlsProofKit kit(String table, String insert, String move) {
    return new RlsProofKit(DBX, table, insert, move);
  }

  @Test
  void budgets() throws Exception {
    kit("budgets", "INSERT INTO planning.budgets (id, account_id, name, period, limit_paise, starts_on)"
        + " VALUES (gen_random_uuid(), ?, 'x', 'monthly', 100000, DATE '2026-01-01')",
        "UPDATE planning.budgets SET account_id = ?").proveAll();
  }

  @Test
  void budgetPeriodSpend() throws Exception {
    kit("budget_period_spend", BUDGET + " INSERT INTO planning.budget_period_spend (account_id, budget_id,"
        + " period_start, spent_paise, projected_version) SELECT account_id, id, DATE '2026-01-01', 500, 1 FROM b",
        "UPDATE planning.budget_period_spend SET account_id = ?").proveAll();
  }

  @Test
  void budgetAlertsSent() throws Exception {
    kit("budget_alerts_sent", BUDGET + " INSERT INTO planning.budget_alerts_sent (account_id, budget_id,"
        + " period_start, threshold, spent_paise) SELECT account_id, id, DATE '2026-01-01', 50, 50000 FROM b",
        null).proveAll(); // append-only: the app role has no UPDATE, so there is no row to move
  }

  @Test
  void liabilities() throws Exception {
    kit("liabilities", "INSERT INTO planning.liabilities (id, account_id, kind, lender, principal_paise, apr_bps,"
        + " tenure_months, start_date, emi_paise) VALUES (gen_random_uuid(), ?, 'loan', 'x', 1000000, 1200, 12,"
        + " DATE '2026-01-01', 88849)", "UPDATE planning.liabilities SET account_id = ?").proveAll();
  }

  @Test
  void liabilitySchedule() throws Exception {
    kit("liability_schedule", LIABILITY + " INSERT INTO planning.liability_schedule (liability_id, account_id,"
        + " installment_no, due_on, principal_paise, interest_paise, total_paise, outstanding_after_paise)"
        + " SELECT id, account_id, 1, DATE '2026-01-01', 78849, 10000, 88849, 921151 FROM l",
        null).proveAll(); // written once and never rewritten (ADR-0019 §7): the app role has no UPDATE
  }

  @Test
  void scenarios() throws Exception {
    kit("scenarios", "INSERT INTO planning.scenarios (id, account_id, name, base_snapshot)"
        + " VALUES (gen_random_uuid(), ?, 'x', '{}'::jsonb)",
        "UPDATE planning.scenarios SET account_id = ?").proveAll();
  }
}
