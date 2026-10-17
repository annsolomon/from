package in.dexter.testkit.containers;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import in.dexter.db.sql.Sql;
import in.dexter.db.template.SqlTemplates;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * A service database inside {@link TestPostgres}: roles {@code <svc>_owner|app|admin|relay|readonly}
 * with the same safety settings as {@code infra/postgres/init/00-roles.sql.tmpl} (NOBYPASSRLS, not
 * superusers, per-role timeouts), the same extensions, and every SQL template applied as the owner.
 * Replaces the P01 {@code PgTestSupport} for everything downstream of dexter-db (amendment A-11/A-14).
 */
public final class ServiceDb {
  public static final String PW = "test-only-pw";
  private final String svc;
  private final boolean templated;

  private ServiceDb(String svc, boolean templated) {
    this.svc = svc;
    this.templated = templated;
  }

  static ServiceDb create(String service) {
    return create(service, true);
  }

  /**
   * @param templates true: apply every SQL template as the owner (library ITs, the demo); false: roles, database
   *                  and extensions only, for a service whose own Flyway migrations (which contain the rendered
   *                  templates) run against it — exactly what infra gives a real service.
   */
  static ServiceDb create(String service, boolean templates) {
    String s = Sql.ident(service);
    try {
      try (Connection c = TestPostgres.superConn("postgres"); Statement st = c.createStatement()) {
        for (String r : new String[] {"owner", "app", "admin", "relay", "readonly"}) {
          st.execute("CREATE ROLE " + s + "_" + r + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION"
              + " NOBYPASSRLS NOINHERIT PASSWORD '" + PW + "'");
          st.execute("ALTER ROLE " + s + "_" + r + " SET search_path = " + s + ", public");
        }
        st.execute("ALTER ROLE " + s + "_owner SET lock_timeout = '10s'");
        st.execute("ALTER ROLE " + s + "_app SET statement_timeout = '5s'");
        st.execute("ALTER ROLE " + s + "_app SET lock_timeout = '2s'");
        st.execute("ALTER ROLE " + s + "_app SET idle_in_transaction_session_timeout = '10s'");
        st.execute("ALTER ROLE " + s + "_admin SET statement_timeout = '10s'");
        st.execute("ALTER ROLE " + s + "_admin SET lock_timeout = '2s'");
        st.execute("ALTER ROLE " + s + "_relay SET statement_timeout = '30s'");
        st.execute("ALTER ROLE " + s + "_relay SET lock_timeout = '5s'");
        st.execute("ALTER ROLE " + s + "_relay SET idle_in_transaction_session_timeout = '30s'");
        st.execute("ALTER ROLE " + s + "_readonly SET statement_timeout = '30s'");
        st.execute("ALTER ROLE " + s + "_readonly SET default_transaction_read_only = on");
        st.execute("CREATE DATABASE " + s + " OWNER " + s + "_owner");
        st.execute("REVOKE ALL ON DATABASE " + s + " FROM PUBLIC");
        st.execute("GRANT CONNECT ON DATABASE " + s + " TO " + s + "_owner, " + s + "_app, " + s + "_admin, "
            + s + "_relay, " + s + "_readonly");
      }
      try (Connection c = TestPostgres.superConn(s); Statement st = c.createStatement()) {
        st.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        st.execute("GRANT USAGE ON SCHEMA public TO " + s + "_owner, " + s + "_app, " + s + "_admin, " + s
            + "_relay, " + s + "_readonly");
        for (String ext : new String[] {"pgcrypto", "citext", "ltree", "pg_trgm", "btree_gist"}) {
          st.execute("CREATE EXTENSION IF NOT EXISTS " + ext + " WITH SCHEMA public");
        }
      }
      ServiceDb db = new ServiceDb(s, templates);
      if (templates) {
        try (Connection c = db.conn("owner"); Statement st = c.createStatement()) {
          st.execute(SqlTemplates.renderAll(s));
        }
      }
      return db;
    } catch (SQLException e) {
      throw new IllegalStateException("creating test database " + s + " failed: " + e.getMessage(), e);
    }
  }

  public String name() {
    return svc;
  }

  /** True when the SQL templates were applied by the kit (false: the service's Flyway owns the schema). */
  public boolean templated() {
    return templated;
  }

  /** {@code <svc>_<suffix>}, for example {@code role("app")} → {@code demo_app}. */
  public String role(String suffix) {
    return svc + "_" + suffix;
  }

  public String url() {
    return TestPostgres.url(svc);
  }

  /** A plain JDBC connection as {@code <svc>_<roleSuffix>}, or as the superuser for {@code "postgres"}. */
  public Connection conn(String roleSuffix) throws SQLException {
    if (roleSuffix.equals("postgres")) {
      return TestPostgres.superConn(svc);
    }
    return DriverManager.getConnection(url(), role(roleSuffix), PW);
  }

  /** Runs SQL (one or many statements) as a role, autocommit. Wraps SQLException with the SQL. */
  public void exec(String roleSuffix, String sql) {
    try (Connection c = conn(roleSuffix); Statement st = c.createStatement()) {
      st.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(sql + " -> " + e.getMessage(), e);
    }
  }

  public HikariDataSource pool(String roleSuffix, int size, String appName) {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(url() + "?ApplicationName=" + appName);
    cfg.setUsername(role(roleSuffix));
    cfg.setPassword(PW);
    cfg.setMaximumPoolSize(size);
    cfg.setPoolName(svc + "-" + appName);
    return new HikariDataSource(cfg);
  }

  /** Non-pooled DataSource (what the outbox relay uses: its session lock must die with the connection). */
  public DataSource unpooled(String roleSuffix) {
    return new SimpleDriverDataSource(new org.postgresql.Driver(), url(), role(roleSuffix), PW);
  }
}
