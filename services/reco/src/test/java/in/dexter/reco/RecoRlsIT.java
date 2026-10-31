package in.dexter.reco;

import in.dexter.reco.support.RecoIT;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.rls.RlsProofKit;
import org.junit.jupiter.api.Test;

/** RLS proof (MASTER_PLAN 10, P14 DoD) on every per-tenant reco table: flags, isolation, fail-closed, WITH CHECK. */
class RecoRlsIT extends RecoIT {
  static final ServiceDb DBX = TestPostgres.flywayDb("reco");

  static RlsProofKit kit(String table, String insert, String move) {
    return new RlsProofKit(DBX, table, insert, move);
  }

  @Test
  void spendMonth() throws Exception {
    kit("spend_month", "INSERT INTO reco.spend_month (account_id, category_root, month, total_paise, txn_count)"
        + " VALUES (?, CAST('food' AS public.ltree), DATE '2026-01-01', 100000, 1)",
        "UPDATE reco.spend_month SET account_id = ?").proveAll();
  }

  @Test
  void spendProfile() throws Exception {
    kit("spend_profile", "INSERT INTO reco.spend_profile (account_id, category_root, window_code,"
        + " monthly_avg_paise, total_paise, txn_count) VALUES (?, CAST('food' AS public.ltree), '3m', 100000,"
        + " 300000, 3)", "UPDATE reco.spend_profile SET account_id = ?").proveAll();
  }

  @Test
  void heldCards() throws Exception {
    kit("held_cards_proj", "INSERT INTO reco.held_cards_proj (account_id, user_card_id, card_product_id,"
        + " projected_version) VALUES (?, gen_random_uuid(), gen_random_uuid(), 1)",
        "UPDATE reco.held_cards_proj SET account_id = ?").proveAll();
  }

  @Test
  void recState() throws Exception {
    kit("rec_state", "INSERT INTO reco.rec_state (account_id, dirty, dirty_since) VALUES (?, true, now())",
        "UPDATE reco.rec_state SET account_id = ?").proveAll();
  }

  @Test
  void recommendations() throws Exception {
    kit("recommendations", "INSERT INTO reco.recommendations (account_id, state_hash, payload)"
        + " VALUES (?, 'hash', '{}'::jsonb)", "UPDATE reco.recommendations SET account_id = ?").proveAll();
  }

  @Test
  void recommendationHistory() throws Exception {
    // append-only for the app role (it has SELECT and INSERT, no UPDATE), so there is no row to move
    kit("recommendation_history", "INSERT INTO reco.recommendation_history (account_id, computed_at, state_hash,"
        + " payload) VALUES (?, now(), 'hash', '{}'::jsonb)", null).proveAll();
  }
}
