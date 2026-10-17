package in.dexter.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.identity.support.IdentityIT;
import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RLS and grants of the P05 admin tables (MASTER_PLAN 3.1, 10). identity_app sees only the admin it acts as;
 * identity_admin reads other admins only as SUPER_ADMIN, accounts only as SUPPORT/FINANCE/SUPER_ADMIN, and writes
 * admins only through the audited definer functions.
 */
class AdminRlsIT extends IdentityIT {
  static final List<String> TABLES = List.of("mfa_totp", "recovery_codes", "roles", "permissions", "role_permissions",
      "admins", "admin_roles", "admin_mfa_totp", "elevations", "admin_sessions", "admin_refresh_tokens",
      "admin_invites", "admin_audit");
  final ServiceDb db = TestPostgres.flywayDb("identity");
  UUID a;
  UUID b;

  @BeforeEach
  void twoAdmins() throws SQLException {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    try (Connection c = db.conn("owner"); Statement s = c.createStatement()) {
      s.execute("INSERT INTO identity.accounts (id, email, password_hash) VALUES (gen_random_uuid(),"
          + " 'rls-acct-' || gen_random_uuid() || '@it.test', repeat('h', 40))");
      for (UUID id : List.of(a, b)) {
        s.execute("INSERT INTO identity.admins (id, email, password_hash, status, break_glass_eligible) VALUES ('" + id
            + "', 'rls-" + id + "@it.test', repeat('h', 40), 'active', true)");
        s.execute("INSERT INTO identity.admin_roles (admin_id, role) VALUES ('" + id + "', 'SUPPORT')");
        s.execute("INSERT INTO identity.admin_sessions (id, admin_id, client_id, amr, auth_time) VALUES"
            + " (gen_random_uuid(), '" + id + "', 'dexter-admin', '{pwd,mfa}', now())");
      }
    }
  }

  @Test
  void everyP05TableHasForcedRls() throws SQLException {
    try (Connection c = db.conn("owner"); Statement s = c.createStatement()) {
      for (String t : TABLES) {
        try (ResultSet rs = s.executeQuery("SELECT relrowsecurity, relforcerowsecurity FROM pg_class"
            + " WHERE oid = 'identity." + t + "'::regclass")) {
          assertThat(rs.next()).as(t).isTrue();
          assertThat(rs.getBoolean(1) && rs.getBoolean(2)).as(t + " ENABLE + FORCE RLS").isTrue();
        }
      }
    }
  }

  @Test
  void appSeesOnlyTheAdminItActsAs() throws SQLException {
    try (Connection c = db.conn("app")) {
      c.setAutoCommit(false);
      try {
        context(c, a, "ADMIN");
        for (String t : List.of("admins", "admin_roles", "admin_sessions")) {
          String col = t.equals("admins") ? "id" : "admin_id";
          assertThat(count(c, "SELECT count(1) FROM identity." + t + " WHERE " + col + " = '" + a + "'")).as(t)
              .isPositive();
          assertThat(count(c, "SELECT count(1) FROM identity." + t + " WHERE " + col + " <> '" + a + "'")).as(t)
              .isZero();
        }
        context(c, null, null);
        assertThat(count(c, "SELECT count(1) FROM identity.admins")).as("no context → nothing").isZero();
      } finally {
        c.rollback();
      }
    }
    for (String sql : List.of(
        "INSERT INTO identity.admin_sessions (id, admin_id, client_id, amr, auth_time) VALUES (gen_random_uuid(), '"
            + b + "', 'x', '{pwd}', now())",
        "INSERT INTO identity.elevations (id, admin_id, reason, starts_at, expires_at) VALUES (gen_random_uuid(), '"
            + b + "', repeat('r', 30), now(), now() + interval '10 minutes')",
        "INSERT INTO identity.admin_audit (id, admin_id, action, target_type) VALUES (gen_random_uuid(), '" + b
            + "', 'FORGED', 'admin')")) {
      assertThatThrownBy(() -> asApp(a, sql)).as(sql).hasMessageContaining("row-level security");
    }
  }

  @Test
  void appCannotChangeItsOwnPrivileges() {
    for (String sql : List.of(
        "UPDATE identity.admins SET break_glass_eligible = true",
        "UPDATE identity.admins SET status = 'active'",
        "INSERT INTO identity.admin_roles (admin_id, role) VALUES ('" + a + "', 'FINANCE')",
        "INSERT INTO identity.admins (id, email, status) VALUES (gen_random_uuid(), 'x@y.z', 'invited')",
        "SELECT count(1) FROM identity.admin_invites",
        "SELECT count(1) FROM identity.admin_audit")) {
      assertThatThrownBy(() -> asApp(a, sql)).as(sql).hasMessageContaining("permission denied");
    }
  }

  @Test
  void adminRoleReadsByActorRole() throws SQLException {
    try (Connection c = db.conn("admin")) {
      c.setAutoCommit(false);
      try {
        context(c, a, "SUPPORT");
        assertThat(count(c, "SELECT count(1) FROM identity.admins")).as("SUPPORT reads no admins").isZero();
        assertThat(count(c, "SELECT count(1) FROM identity.accounts")).as("SUPPORT reads accounts").isPositive();
        context(c, a, "CATALOG_EDITOR");
        assertThat(count(c, "SELECT count(1) FROM identity.accounts")).as("CATALOG_EDITOR: no accounts").isZero();
        assertThat(count(c, "SELECT count(1) FROM identity.admin_audit")).isZero();
        context(c, a, "USER");
        assertThat(count(c, "SELECT count(1) FROM identity.accounts")).isZero();
        context(c, a, "SUPER_ADMIN");
        assertThat(count(c, "SELECT count(1) FROM identity.admins WHERE id IN ('" + a + "', '" + b + "')"))
            .isEqualTo(2);
      } finally {
        c.rollback();
      }
    }
    for (String sql : List.of("UPDATE identity.admins SET status = 'disabled'",
        "INSERT INTO identity.admin_roles (admin_id, role) VALUES ('" + b + "', 'FINANCE')",
        "DELETE FROM identity.admin_audit", "UPDATE identity.accounts SET status = 'disabled'")) {
      assertThatThrownBy(() -> as("admin", a, "SUPER_ADMIN", sql)).as(sql).hasMessageContaining("permission denied");
    }
  }

  @Test
  void definerFunctionsCheckTheActorRole() {
    String create = "SELECT admin_id FROM identity.admin_create(gen_random_uuid(), 'new-" + UUID.randomUUID()
        + "@it.test', null, '{SUPPORT}', false, gen_random_uuid(), sha256(gen_random_uuid()::text::bytea),"
        + " now() + interval '1 day')";
    assertThatThrownBy(() -> as("admin", a, "SUPPORT", create)).hasMessageContaining("SUPER_ADMIN required");
    assertThatThrownBy(() -> as("app", a, "SUPER_ADMIN", create)).hasMessageContaining("permission denied");
    assertThatThrownBy(() -> as("admin", a, "SUPER_ADMIN",
        "SELECT version FROM identity.admin_update('" + a + "', 0, null, 'disabled', null, null, null)"))
        .hasMessageContaining("themselves");
    assertThatThrownBy(() -> as("admin", a, "SUPER_ADMIN", create.replace("'{SUPPORT}'", "'{SUPER_ADMIN}'")))
        .hasMessageContaining("non-standing");
  }

  private void asApp(UUID account, String sql) throws SQLException {
    as("app", account, "ADMIN", sql);
  }

  private void as(String role, UUID account, String actor, String sql) throws SQLException {
    try (Connection c = db.conn(role)) {
      c.setAutoCommit(false);
      try {
        context(c, account, actor);
        try (Statement s = c.createStatement()) {
          s.execute(sql);
        }
      } finally {
        c.rollback();
      }
    }
  }

  private static void context(Connection c, UUID account, String actor) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT set_config('app.account_id', ?, true), set_config('app.actor_role', ?, true)")) {
      ps.setString(1, account == null ? "" : account.toString());
      ps.setString(2, actor == null ? "" : actor);
      ps.execute();
    }
  }

  private static long count(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
