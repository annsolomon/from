package in.dexter.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.catalog.support.CatalogIT;
import in.dexter.catalog.support.Cards;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RLS proof, global-data variant (MASTER_PLAN P09 "public role can't see drafts"): every catalog table is ENABLE +
 * FORCE; catalog_app reads published data only and writes nothing; catalog_admin reads and writes only for the
 * editor roles; published versions are immutable even for an editor; overlapping rules for one scope are rejected.
 */
class CatalogRlsIT extends CatalogIT {
  static final ServiceDb DBX = TestPostgres.flywayDb("catalog");
  static final String[] TABLES = {"issuers", "card_products", "card_product_drafts", "card_product_versions",
      "reward_rules", "milestones", "offers"};

  static long count(Statement s, String sql) throws Exception {
    try (ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void everyTableForcesRowLevelSecurity() throws Exception {
    try (Connection c = DBX.conn("owner"); Statement s = c.createStatement()) {
      for (String t : TABLES) {
        try (ResultSet rs = s.executeQuery("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = 'catalog."
            + t + "'::regclass")) {
          rs.next();
          assertThat(rs.getBoolean(1) && rs.getBoolean(2)).as(t).isTrue();
        }
      }
    }
  }

  @Test
  void thePublicRoleSeesPublishedDataOnlyAndWritesNothing() throws Exception {
    UUID published = publishedCard("rls-pub-" + Cards.suffix(), "Rls Published", "visa", 0);
    UUID draft = newCard(Cards.doc("rls-draft-" + Cards.suffix(), "Rls Draft", newIssuer(), "visa", 0));
    try (Connection c = DBX.conn("app"); Statement s = c.createStatement()) {
      assertThat(count(s, "SELECT count(1) FROM catalog.card_products WHERE id = '" + published + "'")).isEqualTo(1);
      assertThat(count(s, "SELECT count(1) FROM catalog.card_products WHERE id = '" + draft + "'")).isZero();
      assertThat(count(s, "SELECT count(1) FROM catalog.card_products WHERE status <> 'published'")).isZero();
      assertThat(count(s, "SELECT count(1) FROM catalog.reward_rules WHERE card_product_id = '" + published + "'"))
          .isEqualTo(2);
      assertThatThrownBy(() -> s.executeQuery("SELECT count(1) FROM catalog.card_product_drafts"))
          .hasMessageContaining("permission denied");
      assertThatThrownBy(() -> s.executeUpdate("UPDATE catalog.card_products SET name = 'x'"))
          .hasMessageContaining("permission denied");
      assertThatThrownBy(() -> s.executeUpdate("INSERT INTO catalog.issuers (id, code, name, type)"
          + " VALUES (gen_random_uuid(), 'EVIL', 'Evil', 'bank')")).hasMessageContaining("permission denied");
    }
  }

  @Test
  void theAdminPoolActsOnlyForEditorRoles() throws Exception {
    UUID draft = newCard(Cards.doc("rls-adm-" + Cards.suffix(), "Rls Admin", newIssuer(), "visa", 0));
    try (Connection c = DBX.conn("admin")) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("SELECT set_config('app.account_id', gen_random_uuid()::text, true),"
            + " set_config('app.actor_role', 'SUPPORT', true)");
        assertThat(count(s, "SELECT count(1) FROM catalog.card_products")).as("SUPPORT sees nothing").isZero();
        assertThatThrownBy(() -> s.executeUpdate("INSERT INTO catalog.issuers (id, code, name, type)"
            + " VALUES (gen_random_uuid(), 'EVIL', 'Evil', 'bank')")).hasMessageContaining("row-level security");
      } finally {
        c.rollback();
      }
      try (Statement s = c.createStatement()) {
        s.execute("SELECT set_config('app.account_id', gen_random_uuid()::text, true),"
            + " set_config('app.actor_role', 'CATALOG_EDITOR', true)");
        assertThat(count(s, "SELECT count(1) FROM catalog.card_products WHERE id = '" + draft + "'")).isEqualTo(1);
      } finally {
        c.rollback();
      }
    }
  }

  @Test
  void publishedVersionsAreImmutableAndRuleScopesNeverOverlap() throws Exception {
    UUID card = publishedCard("rls-imm-" + Cards.suffix(), "Rls Immutable", "visa", 0);
    try (Connection c = DBX.conn("admin")) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute("SELECT set_config('app.account_id', gen_random_uuid()::text, true),"
            + " set_config('app.actor_role', 'CATALOG_EDITOR', true)");
        assertThatThrownBy(() -> s.executeUpdate("UPDATE catalog.card_product_versions SET snapshot = '{}'"
            + " WHERE card_product_id = '" + card + "'")).hasMessageContaining("immutable");
      } finally {
        c.rollback();
      }
    }
    try (Connection c = DBX.conn("owner"); Statement s = c.createStatement()) {
      assertThatThrownBy(() -> s.executeUpdate("DELETE FROM catalog.card_product_versions WHERE card_product_id = '"
          + card + "'")).as("not even the owner deletes a version").hasMessageContaining("immutable");
      assertThatThrownBy(() -> s.executeUpdate("INSERT INTO catalog.reward_rules (id, card_product_id, version,"
          + " category_path, reward_type, rate_bps, valid) VALUES (gen_random_uuid(), '" + card + "', 1, 'food.delivery',"
          + " 'cashback', 900, tstzrange(now() - interval '1 day', NULL))"))
          .as("an overlapping rule for the same scope").hasMessageContaining("reward_rules_no_overlap");
      s.executeUpdate("INSERT INTO catalog.reward_rules (id, card_product_id, version, category_path, merchant_id,"
          + " reward_type, rate_bps, valid) VALUES (gen_random_uuid(), '" + card + "', 1, 'food.delivery',"
          + " gen_random_uuid(), 'cashback', 900, tstzrange(now() - interval '1 day', NULL))"); // other scope: fine
    }
  }
}
