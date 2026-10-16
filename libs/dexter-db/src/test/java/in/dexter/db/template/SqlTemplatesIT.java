package in.dexter.db.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.db.testsupport.PgTestSupport;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** The templates apply cleanly (PgTestSupport applies them) and enforce the access rules of 3.2/5.2/6.3. */
class SqlTemplatesIT {

  static String one(String role, String sql) throws Exception {
    try (Connection c = PgTestSupport.conn(role); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }

  @Test
  void idempotencyKeysHaveForcedRls() throws Exception {
    assertThat(one("demo_owner",
        "SELECT relrowsecurity::text || relforcerowsecurity::text FROM pg_class WHERE oid = 'demo.idempotency_keys'::regclass"))
        .isEqualTo("truetrue");
    assertThat(one("demo_owner", "SELECT count(1) FROM pg_policy WHERE polrelid = 'demo.idempotency_keys'::regclass"))
        .isEqualTo("3");
  }

  @Test
  void idempotencyKeysAreTenantScoped() throws Exception {
    try (Connection c = PgTestSupport.conn("demo_app"); Statement s = c.createStatement()) {
      c.setAutoCommit(false);
      s.execute("SELECT set_config('app.account_id', '0000000a-0000-7000-8000-0000000000ff', true)");
      s.execute("INSERT INTO demo.idempotency_keys (account_id, idem_key, request_method, request_path, request_hash, state)"
          + " VALUES ('0000000a-0000-7000-8000-0000000000ff', gen_random_uuid(), 'POST', '/v1/x', '\\x00', 'in_progress')");
      assertThatThrownBy(() -> s.execute("INSERT INTO demo.idempotency_keys (account_id, idem_key, request_method, request_path, request_hash, state)"
          + " VALUES ('0000000b-0000-7000-8000-0000000000ff', gen_random_uuid(), 'POST', '/v1/x', '\\x00', 'in_progress')"))
          .hasMessageContaining("row-level security");
      c.rollback();
    }
    assertThat(one("demo_app", "SELECT count(1) FROM demo.idempotency_keys")).isEqualTo("0");
  }

  @Test
  void outboxIsInsertOnlyForAppAndReadableByRelay() throws Exception {
    try (Connection c = PgTestSupport.conn("demo_app"); Statement s = c.createStatement()) {
      s.execute("INSERT INTO demo.outbox (id, topic, message_key, event_type, payload)"
          + " VALUES (gen_random_uuid(), 'dexter.demo.x.v1', 'k', 'demo.x.created', '{}')");
      assertThatThrownBy(() -> s.executeQuery("SELECT id FROM demo.outbox")).hasMessageContaining("permission denied");
    }
    assertThat(Integer.parseInt(one("demo_relay", "SELECT count(1) FROM demo.outbox WHERE published_at IS NULL"))).isPositive();
  }

  @Test
  void partitionFunctionsAreNotCallableByApp() {
    assertThatThrownBy(() -> PgTestSupport.exec("demo_app", "SELECT demo.create_partitions_ahead('outbox', 'day', 1)"))
        .hasMessageContaining("permission denied");
  }

  @Test
  void contextFunctionsFailClosed() throws Exception {
    assertThat(one("demo_app", "SELECT coalesce(demo.app_account()::text, 'NULL')")).isEqualTo("NULL");
    assertThat(SqlTemplates.render("00-base-functions.sql", "ledger")).contains("ledger.app_account()").doesNotContain("{{");
    assertThatThrownBy(() -> SqlTemplates.render("00-base-functions.sql", "Bad;Schema")).isInstanceOf(IllegalArgumentException.class);
  }
}
