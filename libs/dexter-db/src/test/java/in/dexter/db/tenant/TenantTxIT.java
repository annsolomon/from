package in.dexter.db.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import in.dexter.core.tenant.Tenant;
import in.dexter.db.DexterDataSources;
import in.dexter.db.testsupport.PgTestSupport;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P01 DoD: the RLS context never survives commit or rollback on a pooled connection (pool size 1,
 * so every borrow is the same physical connection).
 */
class TenantTxIT {
  static final Tenant A = Tenant.user(UUID.fromString("0000000a-0000-7000-8000-000000000001"));
  static final Tenant B = Tenant.user(UUID.fromString("0000000b-0000-7000-8000-000000000002"));
  static HikariDataSource pool;
  static HikariDataSource replica;
  static JdbcClient jdbc;
  static JdbcTransactionManager tm;
  static TenantTx tx;

  @BeforeAll
  static void setUp() {
    PgTestSupport.exec("demo_owner", """
        CREATE TABLE demo.notes (id uuid PRIMARY KEY, account_id uuid NOT NULL, body text NOT NULL);
        CREATE INDEX notes_account ON demo.notes (account_id, id);
        ALTER TABLE demo.notes ENABLE ROW LEVEL SECURITY;
        ALTER TABLE demo.notes FORCE ROW LEVEL SECURITY;
        CREATE POLICY notes_tenant ON demo.notes FOR ALL TO demo_app
          USING (account_id = (SELECT demo.app_account()))
          WITH CHECK (account_id = (SELECT demo.app_account()));
        GRANT SELECT, INSERT, UPDATE, DELETE ON demo.notes TO demo_app;
        """);
    pool = PgTestSupport.pool("demo_app", 1, "primary", false);
    replica = PgTestSupport.pool("demo_app", 1, "replica", true);
    DataSource routed = DexterDataSources.readWriteRouting(pool, replica);
    jdbc = JdbcClient.create(routed);
    tm = new JdbcTransactionManager(routed);
    tx = new TenantTx(tm, jdbc);
    tx.writeVoid(A, () -> insert(A, "a1"));
    tx.writeVoid(A, () -> insert(A, "a2"));
    tx.writeVoid(B, () -> insert(B, "b1"));
  }

  @AfterAll
  static void tearDown() {
    pool.close();
    replica.close();
  }

  static void insert(Tenant t, String body) {
    jdbc.sql("INSERT INTO demo.notes (id, account_id, body) VALUES (:id, :a, :b)")
        .param("id", UUID.randomUUID()).param("a", t.accountId()).param("b", body).update();
  }

  static long count() {
    return jdbc.sql("SELECT count(id) FROM demo.notes").query(Long.class).single();
  }

  /** Reads outside TenantTx, in a plain transaction on the same pooled connection. */
  static String rawContext() {
    return new TransactionTemplate(tm).execute(s ->
        jdbc.sql("SELECT coalesce(current_setting('app.account_id', true), '<null>') || '|' || count(id) FROM demo.notes")
            .query(String.class).single());
  }

  @Test
  void eachTenantSeesOnlyItsRows() {
    assertThat(tx.read(A, TenantTxIT::count)).isEqualTo(2);
    assertThat(tx.read(B, TenantTxIT::count)).isEqualTo(1);
  }

  @Test
  void contextResetsAfterCommitOnTheSamePooledConnection() {
    assertThat(tx.write(A, TenantTxIT::count)).isEqualTo(2);
    assertThat(rawContext()).isIn("|0", "<null>|0");
  }

  @Test
  void contextResetsAfterRollbackOnTheSamePooledConnection() {
    assertThatThrownBy(() -> tx.write(A, () -> {
      insert(A, "doomed");
      throw new IllegalStateException("boom");
    })).hasMessage("boom");
    assertThat(rawContext()).isIn("|0", "<null>|0");
    assertThat(tx.read(A, TenantTxIT::count)).isEqualTo(2);
  }

  @Test
  void noContextMeansZeroRows() {
    assertThat(rawContext()).endsWith("|0");
  }

  @Test
  void withCheckBlocksWritingAnotherTenantsAccountId() {
    // Postgres reports an RLS WITH CHECK violation with SQLSTATE 42501, which Spring translates to
    // BadSqlGrammarException, so the reason is on the cause, not on the top-level message.
    assertThatThrownBy(() -> tx.writeVoid(A, () -> insert(B, "sneaky")))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("new row violates row-level security policy");
  }

  @Test
  void nestingRules() {
    assertThat(tx.write(A, () -> tx.read(A, TenantTxIT::count))).isEqualTo(2);
    assertThatThrownBy(() -> tx.write(A, () -> tx.read(B, TenantTxIT::count)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("different tenant");
    assertThatThrownBy(() -> tx.read(A, () -> tx.write(A, TenantTxIT::count)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("nested inside");
  }

  @Test
  void scopeGuard() {
    assertThatThrownBy(TenantScope::require).isInstanceOf(IllegalStateException.class);
    assertThat(tx.read(A, TenantScope::require)).isEqualTo(A);
  }

  @Test
  void readOnlyTransactionsRouteToTheReplica() {
    String appRead = tx.read(A, () -> jdbc.sql("SELECT current_setting('application_name')").query(String.class).single());
    String appWrite = tx.write(A, () -> jdbc.sql("SELECT current_setting('application_name')").query(String.class).single());
    assertThat(appRead).isEqualTo("replica");
    assertThat(appWrite).isEqualTo("primary");
    assertThatThrownBy(() -> tx.read(A, () -> {
      insert(A, "ro");
      return null;
    })).hasMessageContaining("read-only");
  }

  @Test
  void appRoleCannotEscapeRls() {
    for (String sql : new String[] {
        "ALTER TABLE demo.notes DISABLE ROW LEVEL SECURITY",
        "SET ROLE demo_owner",
        "SELECT count(*) FROM pg_authid",
        "CREATE TABLE demo.evil (id int)"}) {
      assertThatThrownBy(() -> PgTestSupport.exec("demo_app", sql)).as(sql).isInstanceOf(IllegalStateException.class);
    }
  }
}
