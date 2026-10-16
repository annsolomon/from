package in.dexter.testkit.rls;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.dexter.testkit.containers.ServiceDb;
import in.dexter.testkit.containers.TestPostgres;
import org.junit.jupiter.api.Test;

/** The kit itself fails on a table without FORCE RLS or without a WITH CHECK policy. */
class RlsProofKitIT {
  @Test
  void catchesAMissingForceAndAMissingWithCheck() {
    ServiceDb db = TestPostgres.db("kitcheck");
    db.exec("owner", """
        CREATE TABLE kitcheck.no_force (account_id uuid NOT NULL, v int);
        ALTER TABLE kitcheck.no_force ENABLE ROW LEVEL SECURITY;
        CREATE POLICY p ON kitcheck.no_force FOR ALL TO kitcheck_app
          USING (account_id = (SELECT kitcheck.app_account())) WITH CHECK (account_id = (SELECT kitcheck.app_account()));
        GRANT SELECT, INSERT, UPDATE ON kitcheck.no_force TO kitcheck_app;

        CREATE TABLE kitcheck.no_check (account_id uuid NOT NULL, v int);
        ALTER TABLE kitcheck.no_check ENABLE ROW LEVEL SECURITY;
        ALTER TABLE kitcheck.no_check FORCE ROW LEVEL SECURITY;
        CREATE POLICY p ON kitcheck.no_check FOR ALL TO kitcheck_app USING (true);
        GRANT SELECT, INSERT, UPDATE ON kitcheck.no_check TO kitcheck_app;
        """);
    assertThatThrownBy(() -> new RlsProofKit(db, "no_force", "INSERT INTO kitcheck.no_force VALUES (?, 1)", null)
        .flagsAndPolicies()).isInstanceOf(AssertionError.class).hasMessageContaining("FORCE");
    assertThatThrownBy(() -> new RlsProofKit(db, "no_check", "INSERT INTO kitcheck.no_check VALUES (?, 1)", null)
        .proveAll()).isInstanceOf(AssertionError.class);
  }
}
