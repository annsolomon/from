package in.dexter.testkit.containers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One Postgres 17 container per test JVM (MASTER_PLAN 10), holding one database per service
 * ({@link ServiceDb}), each laid out exactly like infra does it (MASTER_PLAN 3.1).
 */
public final class TestPostgres {
  public static final String IMAGE = "postgres:17";
  private static PostgreSQLContainer pg;
  private static final Map<String, ServiceDb> DBS = new ConcurrentHashMap<>();

  private TestPostgres() {}

  public static synchronized PostgreSQLContainer container() {
    if (pg == null) {
      pg = new PostgreSQLContainer(IMAGE).withUsername("postgres").withPassword("postgres");
      pg.start();
    }
    return pg;
  }

  /** The database of {@code service} (created and templated on first use). */
  public static ServiceDb db(String service) {
    container();
    return DBS.computeIfAbsent(service, ServiceDb::create);
  }

  static String url(String db) {
    PostgreSQLContainer c = container();
    return "jdbc:postgresql://" + c.getHost() + ":" + c.getMappedPort(5432) + "/" + db;
  }

  static Connection superConn(String db) throws SQLException {
    return DriverManager.getConnection(url(db), "postgres", "postgres");
  }
}
