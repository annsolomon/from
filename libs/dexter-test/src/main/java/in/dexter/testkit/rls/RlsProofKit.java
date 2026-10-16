package in.dexter.testkit.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.db.sql.Sql;
import in.dexter.testkit.containers.ServiceDb;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * The per-table RLS proof (MASTER_PLAN 10) for a tenant table with an {@code account_id} column:
 * <ol>
 *   <li>ENABLE + FORCE row level security and at least one policy for {@code <svc>_app};</li>
 *   <li>tenant A cannot read tenant B's rows;</li>
 *   <li>no context → 0 rows (fails closed);</li>
 *   <li>A cannot INSERT or UPDATE a row to B's {@code account_id} (WITH CHECK);</li>
 *   <li>{@code <svc>_app} cannot disable RLS, {@code SET ROLE}, or read another schema's catalog secrets.</li>
 * </ol>
 * The caller supplies an INSERT that creates one row for {@code :account_id} (named parameter, run as
 * the app role inside a tenant context), and an UPDATE statement that tries to move a row to {@code
 * :other_account_id}.
 */
public final class RlsProofKit {
  private final ServiceDb db;
  private final String table;
  private final String insertOneSql;
  private final String moveToOtherSql;

  /**
   * @param table          unqualified table name in the service schema
   * @param insertOneSql   INSERT creating one row, using {@code ?} for the account id (single parameter)
   * @param moveToOtherSql UPDATE ... SET account_id = ? (single parameter, the other account); may be null
   *                       when the app role has no UPDATE on the table
   */
  public RlsProofKit(ServiceDb db, String table, String insertOneSql, String moveToOtherSql) {
    this.db = db;
    this.table = db.name() + "." + Sql.ident(table);
    this.insertOneSql = insertOneSql;
    this.moveToOtherSql = moveToOtherSql;
  }

  /** Runs every check. Leaves no rows behind (all inserts are rolled back). */
  public void proveAll() throws SQLException {
    flagsAndPolicies();
    isolationAndFailClosed();
    withCheckBlocksForeignAccount();
    appCannotEscape();
  }

  public void flagsAndPolicies() throws SQLException {
    try (Connection c = db.conn("owner"); Statement s = c.createStatement();
         ResultSet rs = s.executeQuery("SELECT c.relrowsecurity, c.relforcerowsecurity,"
             + " (SELECT count(1) FROM pg_policy p WHERE p.polrelid = c.oid"
             + "   AND (p.polroles = '{0}' OR (SELECT oid FROM pg_roles WHERE rolname = '" + db.role("app") + "') = ANY(p.polroles)))"
             + " FROM pg_class c WHERE c.oid = '" + table + "'::regclass")) {
      assertThat(rs.next()).as(table + " exists").isTrue();
      assertThat(rs.getBoolean(1)).as(table + " ENABLE ROW LEVEL SECURITY").isTrue();
      assertThat(rs.getBoolean(2)).as(table + " FORCE ROW LEVEL SECURITY").isTrue();
      assertThat(rs.getLong(3)).as(table + " has a policy for " + db.role("app")).isPositive();
    }
  }

  public void isolationAndFailClosed() throws SQLException {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    try (Connection c = db.conn("app")) {
      c.setAutoCommit(false);
      try {
        tenant(c, a);
        insert(c, a);
        tenant(c, b);
        insert(c, b);
        tenant(c, a);
        assertThat(count(c, "account_id <> '" + a + "'")).as(table + ": A sees no foreign rows").isZero();
        assertThat(count(c, "account_id = '" + a + "'")).as(table + ": A sees its own rows").isPositive();
        tenant(c, b);
        assertThat(count(c, "account_id = '" + a + "'")).as(table + ": B cannot read A").isZero();
        clearTenant(c);
        assertThat(count(c, "true")).as(table + ": no context → 0 rows").isZero();
      } finally {
        c.rollback();
      }
    }
  }

  public void withCheckBlocksForeignAccount() throws SQLException {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    try (Connection c = db.conn("app")) {
      c.setAutoCommit(false);
      try {
        tenant(c, a);
        assertThatThrownBy(() -> insert(c, b)).as(table + ": A inserting B's account_id")
            .hasMessageContaining("row-level security");
      } finally {
        c.rollback();
      }
      if (moveToOtherSql != null) {
        try {
          tenant(c, a);
          insert(c, a);
          assertThatThrownBy(() -> {
            try (PreparedStatement ps = c.prepareStatement(moveToOtherSql)) {
              ps.setObject(1, b);
              ps.executeUpdate();
            }
          }).as(table + ": A moving a row to B").hasMessageContaining("row-level security");
        } finally {
          c.rollback();
        }
      }
    }
  }

  public void appCannotEscape() {
    for (String sql : new String[] {
        "ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY",
        "ALTER TABLE " + table + " NO FORCE ROW LEVEL SECURITY",
        "SET ROLE " + db.role("owner"),
        "SET ROLE postgres",
        "SELECT count(1) FROM pg_authid"}) {
      assertThatThrownBy(() -> db.exec("app", sql)).as(db.role("app") + ": " + sql)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  private void insert(Connection c, UUID account) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(insertOneSql)) {
      ps.setObject(1, account);
      ps.executeUpdate();
    }
  }

  private long count(Connection c, String where) throws SQLException {
    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(1) FROM " + table + " WHERE " + where)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static void tenant(Connection c, UUID account) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT set_config('app.account_id', ?, true), set_config('app.actor_role', 'USER', true)")) {
      ps.setString(1, account.toString());
      ps.execute();
    }
  }

  private static void clearTenant(Connection c) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute("SELECT set_config('app.account_id', '', true)");
    }
  }
}
