package in.dexter.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.io.TempDir;

/**
 * The loader against real Postgres 17 with the infra role settings ({@link ServiceDb}): staging while a table has
 * no migration, the real table once it exists (FORCE RLS off only inside the load transaction), refusal to
 * overwrite without {@code --replace}, and a clear failure when a migration lacks a seeded column (ADR-0008).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeedLoadIT {
  @TempDir
  static Path tmp;
  static Path dataset;
  static final Map<String, ServiceDb> DBS = new HashMap<>();
  static final List<String> LOG = new ArrayList<>();

  @BeforeAll
  static void generateAndCreateDatabases() {
    dataset = tmp.resolve("small");
    new Generator(new Generator.Config(42, Size.parse("custom:12,2000,30"), dataset, null, GeneratorTest.PW)).run();
    for (String svc : List.of("identity", "ledger", "catalog", "portfolio")) {
      DBS.put(svc, TestPostgres.db(svc));
    }
  }

  static List<Loader.Loaded> load(boolean replace) {
    return new Loader(LOG::add).load(dataset, svc -> new Loader.Target(DBS.get(svc).url(),
        DBS.get(svc).role("owner"), ServiceDb.PW), replace);
  }

  static long scalar(ServiceDb db, String role, String sql) throws SQLException {
    try (Connection c = db.conn(role); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  @Order(1)
  void withoutMigrationsEverythingGoesToTheOwnerOnlyStagingSchema() throws SQLException {
    List<Loader.Loaded> loaded = load(false);
    assertThat(loaded).hasSize(Tables.ALL.size()).allSatisfy(l -> assertThat(l.into()).startsWith("seed_stage."));
    assertThat(scalar(DBS.get("ledger"), "owner", "SELECT count(*) FROM seed_stage.expenses")).isEqualTo(2000);
    assertThat(scalar(DBS.get("identity"), "owner", "SELECT count(*) FROM seed_stage.accounts")).isEqualTo(12);
    // the runtime role cannot reach staging data
    assertThatThrownBy(() -> scalar(DBS.get("ledger"), "app", "SELECT count(*) FROM seed_stage.expenses"))
        .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
    // reloading staging is a reset, not a duplicate
    load(false);
    assertThat(scalar(DBS.get("ledger"), "owner", "SELECT count(*) FROM seed_stage.expenses")).isEqualTo(2000);
  }

  @Test
  @Order(2)
  void aRealForceRlsTableIsLoadedAndStaysForced() throws SQLException {
    ServiceDb ledger = DBS.get("ledger");
    ledger.exec("owner", """
        CREATE TABLE ledger.categories (
          id uuid PRIMARY KEY, account_id uuid, path ltree NOT NULL, name text NOT NULL, icon text,
          is_system boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT now());
        ALTER TABLE ledger.categories ENABLE ROW LEVEL SECURITY;
        ALTER TABLE ledger.categories FORCE ROW LEVEL SECURITY;
        CREATE POLICY categories_read ON ledger.categories FOR SELECT TO ledger_app
          USING (account_id IS NULL OR account_id = (SELECT ledger.app_account()));
        GRANT SELECT ON ledger.categories TO ledger_app;
        CREATE TABLE ledger.expenses (
          id uuid PRIMARY KEY, account_id uuid NOT NULL, occurred_at timestamptz NOT NULL,
          amount_paise bigint NOT NULL CHECK (amount_paise > 0), currency char(3) NOT NULL DEFAULT 'INR',
          merchant_raw text, merchant_display text, merchant_id uuid, category_path ltree NOT NULL,
          category_source text NOT NULL CHECK (category_source IN ('user','resolver','import','rule')),
          resolution_confidence smallint CHECK (resolution_confidence BETWEEN 0 AND 1000),
          payment_method text NOT NULL CHECK (payment_method IN ('card','upi','cash','netbanking','wallet','other')),
          user_card_id uuid, note text CHECK (length(note) <= 500), tags text[] CHECK (cardinality(tags) <= 10),
          client_ref text, source text NOT NULL CHECK (source IN ('manual','import','statement','sync')),
          created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, version integer NOT NULL DEFAULT 0,
          deleted_at timestamptz);
        CREATE UNIQUE INDEX expenses_client_ref ON ledger.expenses (account_id, client_ref) WHERE client_ref IS NOT NULL;
        ALTER TABLE ledger.expenses ENABLE ROW LEVEL SECURITY;
        ALTER TABLE ledger.expenses FORCE ROW LEVEL SECURITY;
        CREATE POLICY expenses_tenant ON ledger.expenses FOR ALL TO ledger_app
          USING (account_id = (SELECT ledger.app_account())) WITH CHECK (account_id = (SELECT ledger.app_account()));
        GRANT SELECT, INSERT, UPDATE, DELETE ON ledger.expenses TO ledger_app;
        """);
    List<Loader.Loaded> loaded = load(true);
    assertThat(loaded).filteredOn(l -> l.service().equals("ledger") && l.table().equals("expenses"))
        .singleElement().satisfies(l -> {
          assertThat(l.into()).isEqualTo("ledger.expenses");
          assertThat(l.rows()).isEqualTo(2000);
        });
    assertThat(loaded).filteredOn(l -> l.table().equals("categories")).singleElement()
        .satisfies(l -> assertThat(l.into()).isEqualTo("ledger.categories"));
    assertThat(scalar(ledger, "owner",
        "SELECT count(*) FROM pg_class WHERE relname IN ('expenses','categories') AND relforcerowsecurity"
            + " AND relnamespace = 'ledger'::regnamespace")).isEqualTo(2);
    // forced again: the owner without a policy sees nothing, the app role sees one tenant
    assertThat(scalar(ledger, "owner", "SELECT count(*) FROM ledger.expenses")).isZero();
    assertThat(scalar(ledger, "app", "SELECT count(*) FROM ledger.expenses")).isZero();
    String asha;
    try (Connection c = DBS.get("identity").conn("owner"); Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT id FROM seed_stage.accounts WHERE email = 'asha@demo.in'")) {
      rs.next();
      asha = rs.getString(1);
    }
    try (Connection c = ledger.conn("app")) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.execute("SELECT set_config('app.account_id', '" + asha + "', true)");
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM ledger.expenses")) {
          rs.next();
          assertThat(rs.getLong(1)).isEqualTo(500);
        }
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM ledger.categories")) {
          rs.next();
          assertThat(rs.getLong(1)).isEqualTo(RefData.get().categories.size());
        }
      }
      c.rollback();
    }
  }

  @Test
  @Order(3)
  void aLoadedRealTableIsNotOverwrittenWithoutReplace() throws SQLException {
    assertThatThrownBy(() -> load(false)).hasMessageContaining("already has rows")
        .hasMessageContaining("--replace");
    load(true);
    assertThat(scalar(DBS.get("ledger"), "postgres", "SELECT count(*) FROM ledger.expenses")).isEqualTo(2000);
  }

  @Test
  @Order(4)
  void aMigrationMissingASeededColumnFailsLoudlyAndRollsBack() throws SQLException {
    ServiceDb catalog = DBS.get("catalog");
    catalog.exec("owner", "CREATE TABLE catalog.issuers (id uuid PRIMARY KEY, code text NOT NULL, name text NOT NULL)");
    assertThatThrownBy(() -> load(true)).hasMessageContaining("catalog.issuers exists but lacks seeded columns [type]")
        .hasMessageContaining("Tables.ISSUERS");
    assertThat(scalar(catalog, "owner", "SELECT count(*) FROM catalog.issuers")).isZero();
  }
}
