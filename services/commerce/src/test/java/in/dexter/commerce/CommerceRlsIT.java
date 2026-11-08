package in.dexter.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.commerce.support.CommerceIT;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import in.dexter.testkit.rls.RlsProofKit;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RLS proof (MASTER_PLAN 10) on commerce. The user-written tenant tables get the full kit; the tables only the
 * TRACKER writes (raw clicks, impressions, conversions) are proven by hand: a user can neither write them nor read
 * another account's rows; global deals are written only by operators on the admin pool and never shown as drafts;
 * operators see every account's applications and a USER actor on the admin pool sees none.
 */
class CommerceRlsIT extends CommerceIT {
  static final ServiceDb DBX = TestPostgres.flywayDb("commerce");
  static final String DEAL = "0192f1e2-0000-7000-8000-0000000d7001";

  static RlsProofKit kit(String table, String insert, String move) {
    return new RlsProofKit(DBX, table, insert, move);
  }

  @Test
  void cardApplications() throws Exception {
    kit("card_applications", "INSERT INTO commerce.card_applications (id, account_id, card_product_id, status,"
        + " created_at, updated_at) VALUES (gen_random_uuid(), ?, gen_random_uuid(), 'submitted', now(), now())",
        "UPDATE commerce.card_applications SET account_id = ?").proveAll();
  }

  @Test
  void heldCardsProjection() throws Exception {
    kit("held_cards_proj", "INSERT INTO commerce.held_cards_proj (account_id, user_card_id, card_product_id,"
        + " projected_version) VALUES (?, gen_random_uuid(), gen_random_uuid(), 1)",
        "UPDATE commerce.held_cards_proj SET account_id = ?").proveAll();
  }

  @Test
  void adFreeProjection() throws Exception {
    kit("ad_free_proj", "INSERT INTO commerce.ad_free_proj (account_id, tier, ad_free, projected_version)"
        + " VALUES (?, 'PREMIUM', true, 1)", "UPDATE commerce.ad_free_proj SET account_id = ?").proveAll();
  }

  static void actor(Connection c, UUID account, String role) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT set_config('app.account_id', ?, true), set_config('app.actor_role', ?, true)")) {
      ps.setString(1, account.toString());
      ps.setString(2, role);
      ps.execute();
    }
  }

  static long count(Connection c, String sql) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  static void exec(Connection c, String sql) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.executeUpdate();
    }
  }

  @Test
  void trackerTablesAreWrittenOnlyByTheTrackerAndReadOnlyByTheirAccount() throws Exception {
    new RlsProofKit(DBX, "deal_clicks", "x", null).flagsAndPolicies();
    new RlsProofKit(DBX, "conversions", "x", null).flagsAndPolicies();
    new RlsProofKit(DBX, "ad_impressions_raw", "x", null).flagsAndPolicies();
    new RlsProofKit(DBX, "deal_click_counters", "x", null).flagsAndPolicies();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    String click = "INSERT INTO commerce.deal_clicks (id, created_at, account_id, deal_id, click_token_hash, ip_hash,"
        + " ua_hash) VALUES (gen_random_uuid(), now(), '%s', '" + DEAL + "', sha256('t'), sha256('i'), sha256('u'))";
    String conv = "INSERT INTO commerce.conversions (id, network, provider_ref, account_id, amount_paise,"
        + " commission_paise, status, occurred_at) VALUES (gen_random_uuid(), 'mock', 'rls-' || gen_random_uuid(),"
        + " '%s', 100, 4, 'pending', now())";
    String imp = "INSERT INTO commerce.ad_impressions_raw (id, created_at, creative_id, campaign_id, placement,"
        + " account_id, visitor_hash) VALUES (gen_random_uuid(), now(), gen_random_uuid(), gen_random_uuid(), 'home',"
        + " '%s', sha256('v'))";
    try (Connection c = DBX.conn("app")) {
      c.setAutoCommit(false);
      try {
        actor(c, UUID.randomUUID(), "TRACKER");
        for (String sql : new String[] {click, conv, imp}) {
          exec(c, String.format(sql, a));
          exec(c, String.format(sql, b));
        }
        exec(c, "INSERT INTO commerce.deal_click_counters (deal_id, day, clicks) VALUES ('" + DEAL
            + "', current_date, 2)");
        actor(c, a, "USER");
        for (String t : new String[] {"deal_clicks", "conversions", "ad_impressions_raw"}) {
          assertThat(count(c, "SELECT count(1) FROM commerce." + t + " WHERE account_id = '" + a + "'"))
              .as(t + ": A sees its own").isPositive();
          assertThat(count(c, "SELECT count(1) FROM commerce." + t + " WHERE account_id <> '" + a + "'"))
              .as(t + ": A sees nobody else's").isZero();
        }
        assertThat(count(c, "SELECT count(1) FROM commerce.deal_click_counters")).as("counters: users see none")
            .isZero();
      } finally {
        c.rollback();
      }
      for (String sql : new String[] {click, conv, imp}) {
        try {
          actor(c, a, "USER");
          assertThatThrownBy(() -> exec(c, String.format(sql, a))).as("a USER writing tracker rows: " + sql)
              .hasMessageContaining("row-level security");
        } finally {
          c.rollback();
        }
      }
    }
    new RlsProofKit(DBX, "deal_clicks", "x", null).appCannotEscape();
  }

  @Test
  void dealsAreOperatorWrittenAndDraftsStayHidden() throws Exception {
    String insert = "INSERT INTO commerce.deals (id, merchant_id, merchant_name, title, category_path, components,"
        + " reference_spend_paise, valid, status, network, landing_url) VALUES (gen_random_uuid(), gen_random_uuid(),"
        + " 'M', 'T', 'shopping', '[{\"kind\":\"flat\",\"valuePaise\":100}]', 1000, tstzrange(now(), null), '%s',"
        + " 'mock', 'https://m.example/p')";
    try (Connection c = DBX.conn("admin")) {
      c.setAutoCommit(false);
      try {
        actor(c, UUID.randomUUID(), "USER");
        assertThatThrownBy(() -> exec(c, String.format(insert, "active"))).as("a USER actor on the admin pool")
            .hasMessageContaining("row-level security");
      } finally {
        c.rollback();
      }
      try {
        actor(c, UUID.randomUUID(), "CATALOG_EDITOR");
        exec(c, String.format(insert, "draft"));
        c.commit();
      } finally {
        c.rollback();
      }
    }
    try (Connection c = DBX.conn("app")) {
      c.setAutoCommit(false);
      try {
        actor(c, new UUID(0, 0), "PUBLIC");
        assertThat(count(c, "SELECT count(1) FROM commerce.deals WHERE status = 'draft'")).isZero();
        assertThatThrownBy(() -> exec(c, String.format(insert, "active"))).as("commerce_app writing a deal")
            .hasMessageContaining("permission denied");
      } finally {
        c.rollback();
      }
    }
  }

  @Test
  void operatorsSeeEveryAccountsApplicationsAndOthersNone() throws Exception {
    UUID a = UUID.randomUUID();
    ADMIN.sql("INSERT INTO commerce.card_applications (id, account_id, card_product_id, status, created_at,"
        + " updated_at) VALUES (gen_random_uuid(), :a, gen_random_uuid(), 'submitted', now(), now())")
        .param("a", a).update();
    try (Connection c = DBX.conn("admin")) {
      c.setAutoCommit(false);
      try {
        actor(c, UUID.randomUUID(), "SUPPORT");
        assertThat(count(c, "SELECT count(1) FROM commerce.card_applications WHERE account_id = '" + a + "'"))
            .isEqualTo(1);
        actor(c, a, "USER");
        assertThat(count(c, "SELECT count(1) FROM commerce.card_applications")).as("USER on the admin pool")
            .isZero();
        actor(c, UUID.randomUUID(), "FINANCE");
        assertThat(count(c, "SELECT count(1) FROM commerce.card_applications")).as("FINANCE is no operator")
            .isZero();
      } finally {
        c.rollback();
      }
    }
  }
}
