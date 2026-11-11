package in.dexter.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.audit.support.AuditIT;
import in.dexter.testkit.rls.RlsProofKit;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RLS (MASTER_PLAN 3.2) on audit's tables: the full kit on the three tables a user writes (their inbox, their
 * preferences, their privacy requests); activity and the hourly aggregate written by SYSTEM alone; the log, the
 * anchors and the chain head read by operators alone (the admin pool), never by a user.
 */
class AuditRlsIT extends AuditIT {

  static RlsProofKit kit(String table, String insert, String move) {
    return new RlsProofKit(DB, table, insert, move);
  }

  @Test
  void notifications() throws Exception {
    kit("audit.notifications", "INSERT INTO audit.notifications (id, account_id, channel, category, template, payload,"
        + " status, source_event_id) VALUES (gen_random_uuid(), ?, 'inapp', 'budget', 'budget.threshold', '{}',"
        + " 'sent', gen_random_uuid())", "UPDATE audit.notifications SET account_id = ?").proveAll();
  }

  @Test
  void notificationPrefs() throws Exception {
    kit("audit.notification_prefs", "INSERT INTO audit.notification_prefs (account_id) VALUES (?)",
        "UPDATE audit.notification_prefs SET account_id = ?").proveAll();
  }

  @Test
  void privacyRequests() throws Exception {
    kit("audit.privacy_requests", "INSERT INTO audit.privacy_requests (id, account_id, kind, status, phase, services,"
        + " next_check_at) VALUES (gen_random_uuid(), ?, 'export', 'running', 'export', '{}', now())", null).proveAll();
    // a user may create only a running request, and never move one along themselves
    UUID a = UUID.randomUUID();
    try (Connection c = DB.conn("app")) {
      c.setAutoCommit(false);
      try {
        actor(c, a, "USER");
        assertThatThrownBy(() -> exec(c, "INSERT INTO audit.privacy_requests (id, account_id, kind, status, phase,"
            + " services) VALUES (gen_random_uuid(), '" + a + "', 'delete', 'completed', 'done', '{}')"))
            .hasMessageContaining("row-level security");
      } finally {
        c.rollback();
      }
      try {
        actor(c, a, "USER");
        exec(c, "INSERT INTO audit.privacy_requests (id, account_id, kind, status, phase, services, next_check_at)"
            + " VALUES (gen_random_uuid(), '" + a + "', 'delete', 'running', 'freeze', '{}', now())");
        assertThat(update(c, "UPDATE audit.privacy_requests SET status = 'completed', next_check_at = NULL"))
            .as("no user UPDATE policy").isZero();
      } finally {
        c.rollback();
      }
    }
  }

  @Test
  void systemWritesActivityAndUsersReadOnlyTheirOwn() throws Exception {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    String insert = "INSERT INTO audit.activity_events (id, occurred_at, account_id, request_id, service,"
        + " route_pattern, route_class, method, status, duration_ms) VALUES (gen_random_uuid(), now(), '%s', 'r',"
        + " 'ledger', '/v1/x', 'read', 'GET', 200, 1)";
    try (Connection c = DB.conn("app")) {
      c.setAutoCommit(false);
      try {
        actor(c, a, "USER");
        assertThatThrownBy(() -> exec(c, insert.formatted(a))).hasMessageContaining("row-level security");
      } finally {
        c.rollback();
      }
      try {
        actor(c, UUID.randomUUID(), "SYSTEM");
        exec(c, insert.formatted(a));
        exec(c, insert.formatted(b));
        exec(c, "INSERT INTO audit.activity_hourly (hour, service, route_class, requests) VALUES"
            + " (date_trunc('hour', now()), 'rlsprobe', 'read', 1)");
        actor(c, a, "USER");
        assertThat(count(c, "SELECT count(1) FROM audit.activity_events WHERE account_id = '" + a + "'")).isEqualTo(1);
        assertThat(count(c, "SELECT count(1) FROM audit.activity_events WHERE account_id = '" + b + "'")).isZero();
        assertThat(count(c, "SELECT count(1) FROM audit.activity_hourly")).as("operators only").isZero();
      } finally {
        c.rollback();
      }
    }
  }

  @Test
  void theLogAnchorsAndHeadAreForOperatorsOnly() throws Exception {
    long seq = count("SELECT seq FROM audit.chain_head");
    assertThat(seq).isNotNegative();
    try (Connection app = DB.conn("app")) {
      // no grant at all: each statement on its own (autocommit), whatever the actor
      assertThatThrownBy(() -> count(app, "SELECT count(1) FROM audit.audit_log"))
          .hasMessageContaining("permission denied");
      assertThatThrownBy(() -> count(app, "SELECT count(1) FROM audit.chain_head"))
          .hasMessageContaining("permission denied");
    }
    try (Connection admin = DB.conn("admin")) {
      admin.setAutoCommit(false);
      try {
        actor(admin, UUID.randomUUID(), "FINANCE");
        assertThat(count(admin, "SELECT count(1) FROM audit.chain_head")).as("not an audit operator").isZero();
        actor(admin, UUID.randomUUID(), "SUPPORT");
        assertThat(count(admin, "SELECT count(1) FROM audit.chain_head")).isEqualTo(1);
        assertThat(count(admin, "SELECT count(1) FROM audit.audit_log")).isEqualTo(seq);
      } finally {
        admin.rollback();
      }
    }
  }

  static void actor(Connection c, UUID account, String role) throws Exception {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT set_config('app.account_id', ?, true), set_config('app.actor_role', ?, true)")) {
      ps.setString(1, account.toString());
      ps.setString(2, role);
      ps.execute();
    }
  }

  static void exec(Connection c, String sql) throws Exception {
    try (Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  static int update(Connection c, String sql) throws Exception {
    try (Statement s = c.createStatement()) {
      return s.executeUpdate(sql);
    }
  }

  static long count(Connection c, String sql) throws Exception {
    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
