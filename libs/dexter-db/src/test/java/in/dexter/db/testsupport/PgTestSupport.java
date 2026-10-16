package in.dexter.db.testsupport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import in.dexter.db.template.SqlTemplates;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres 17 container per test JVM, with a {@code demo} database laid out exactly like a
 * service database (MASTER_PLAN 3.1): roles demo_owner/app/admin/relay/readonly (NOBYPASSRLS, not
 * superusers), per-role settings, extensions, and all SQL templates applied as demo_owner.
 * Moves to dexter-test as a shared kit in P02.
 */
public final class PgTestSupport {
  public static final String DB = "demo";
  public static final String PW = "test-only-pw";
  private static PostgreSQLContainer pg;

  private PgTestSupport() {}

  public static synchronized PostgreSQLContainer pg() {
    if (pg == null) {
      pg = new PostgreSQLContainer("postgres:17").withUsername("postgres").withPassword("postgres");
      pg.start();
      try {
        bootstrap();
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    }
    return pg;
  }

  private static void bootstrap() throws SQLException {
    try (Connection c = superConn("postgres"); Statement s = c.createStatement()) {
      for (String r : new String[] {"owner", "app", "admin", "relay", "readonly"}) {
        s.execute("CREATE ROLE demo_" + r + " LOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT PASSWORD '" + PW + "'");
        s.execute("ALTER ROLE demo_" + r + " SET search_path = demo, public");
      }
      s.execute("ALTER ROLE demo_app SET statement_timeout = '5s'");
      s.execute("ALTER ROLE demo_app SET lock_timeout = '2s'");
      s.execute("CREATE DATABASE demo OWNER demo_owner");
    }
    try (Connection c = superConn(DB); Statement s = c.createStatement()) {
      s.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
      s.execute("GRANT USAGE ON SCHEMA public TO demo_owner, demo_app, demo_admin, demo_relay, demo_readonly");
      for (String ext : new String[] {"pgcrypto", "citext", "ltree", "pg_trgm", "btree_gist"}) {
        s.execute("CREATE EXTENSION IF NOT EXISTS " + ext + " WITH SCHEMA public");
      }
    }
    try (Connection c = conn("demo_owner"); Statement s = c.createStatement()) {
      s.execute(SqlTemplates.renderAll("demo"));
    }
  }

  public static Connection superConn(String db) throws SQLException {
    return DriverManager.getConnection(url(db), "postgres", "postgres");
  }

  /** A plain JDBC connection to the demo database as a role (owner for DDL, app for RLS checks). */
  public static Connection conn(String role) throws SQLException {
    pg();
    return DriverManager.getConnection(url(DB), role, PW);
  }

  public static String url(String db) {
    return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + db;
  }

  /** Executes DDL/DML as a role, autocommit. */
  public static void exec(String role, String sql) {
    try (Connection c = role.equals("postgres") ? superConn(DB) : conn(role); Statement s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(sql + " -> " + e.getMessage(), e);
    }
  }

  public static HikariDataSource pool(String role, int size, String appName, boolean readOnly) {
    pg();
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(url(DB) + "?ApplicationName=" + appName);
    cfg.setUsername(role);
    cfg.setPassword(PW);
    cfg.setMaximumPoolSize(size);
    cfg.setReadOnly(readOnly);
    cfg.setPoolName(appName);
    return new HikariDataSource(cfg);
  }
}
