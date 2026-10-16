package in.dexter.testkit.demo;

import in.dexter.testkit.demo.config.DemoApp;
import in.dexter.testkit.rls.RlsProofKit;
import org.junit.jupiter.api.Test;

/** RlsProofKit on every tenant table of the demo database (MASTER_PLAN 10). */
class DemoRlsIT {
  final DemoApp app = DemoApp.get();

  @Test
  void notes() throws Exception {
    new RlsProofKit(app.db, "notes",
        "INSERT INTO demo.notes (account_id, id, title, kind, amount_paise, created_at)"
            + " VALUES (?, gen_random_uuid(), 't', 'note', 1, now())",
        "UPDATE demo.notes SET account_id = ?").proveAll();
  }

  @Test
  void noteStats() throws Exception {
    new RlsProofKit(app.db, "note_stats",
        "INSERT INTO demo.note_stats (account_id, notes, amount_paise) VALUES (?, 1, 1)"
            + " ON CONFLICT (account_id) DO UPDATE SET notes = note_stats.notes + 1",
        "UPDATE demo.note_stats SET account_id = ?").proveAll();
  }

  @Test
  void noteTitles() throws Exception {
    new RlsProofKit(app.db, "note_titles",
        "INSERT INTO demo.note_titles (account_id, note_id, title, projected_version)"
            + " VALUES (?, gen_random_uuid(), 't', 1)",
        "UPDATE demo.note_titles SET account_id = ?").proveAll();
  }

  @Test
  void idempotencyKeys() throws Exception {
    new RlsProofKit(app.db, "idempotency_keys",
        "INSERT INTO demo.idempotency_keys (account_id, idem_key, request_method, request_path, request_hash, state)"
            + " VALUES (?, gen_random_uuid(), 'POST', '/x', '\\x00', 'in_progress')",
        "UPDATE demo.idempotency_keys SET account_id = ?").proveAll();
  }
}
