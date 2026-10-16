"""Self-test for rls_check.py, run by `verify.sh static` so the gate itself is proven."""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from rls_check import check_sql  # noqa: E402

GOOD = """
CREATE TABLE ledger.expenses (
  id uuid PRIMARY KEY,
  account_id uuid NOT NULL,
  amount_paise bigint NOT NULL CHECK (amount_paise > 0)
);
ALTER TABLE ledger.expenses ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.expenses FORCE  ROW LEVEL SECURITY;
CREATE POLICY expenses_tenant ON ledger.expenses FOR ALL TO ledger_app
  USING (account_id = (SELECT ledger.app_account()))
  WITH CHECK (account_id = (SELECT ledger.app_account()));

CREATE TABLE ledger.categories (id uuid PRIMARY KEY, status text NOT NULL);
ALTER TABLE ledger.categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger.categories FORCE ROW LEVEL SECURITY;
CREATE POLICY categories_read ON ledger.categories FOR SELECT TO ledger_app USING (status = 'published');

-- rls:exempt ledger.outbox guarded by grants (MASTER_PLAN 6.3)
CREATE TABLE ledger.outbox (id uuid NOT NULL, created_at timestamptz NOT NULL) PARTITION BY RANGE (created_at);
CREATE TABLE ledger.outbox_2026_09 PARTITION OF ledger.outbox FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
"""


class RlsCheckTest(unittest.TestCase):
    def test_good_set_passes(self):
        self.assertEqual(check_sql(GOOD), [])

    def test_missing_force_fails(self):
        errs = check_sql(GOOD.replace("ALTER TABLE ledger.expenses FORCE  ROW LEVEL SECURITY;", ""))
        self.assertIn("expenses: missing FORCE ROW LEVEL SECURITY", errs)

    def test_missing_enable_fails(self):
        errs = check_sql(GOOD.replace("ALTER TABLE ledger.categories ENABLE ROW LEVEL SECURITY;", ""))
        self.assertIn("categories: missing ENABLE ROW LEVEL SECURITY", errs)

    def test_missing_policy_fails(self):
        errs = check_sql(GOOD.replace("CREATE POLICY categories_read", "-- CREATE POLICY categories_read"))
        self.assertIn("categories: no CREATE POLICY", errs)

    def test_tenant_policy_must_use_app_account(self):
        bad = GOOD.replace("(SELECT ledger.app_account())", "'00000000-0000-0000-0000-000000000000'::uuid")
        self.assertIn("expenses: tenant table (account_id) but no policy uses app_account()", check_sql(bad))

    def test_unexempted_outbox_fails(self):
        errs = check_sql(GOOD.replace("-- rls:exempt ledger.outbox", "-- note"))
        self.assertIn("outbox: missing ENABLE ROW LEVEL SECURITY", errs)

    def test_commented_out_statements_do_not_count(self):
        bad = GOOD.replace("ALTER TABLE ledger.expenses ENABLE", "-- ALTER TABLE ledger.expenses ENABLE")
        self.assertIn("expenses: missing ENABLE ROW LEVEL SECURITY", check_sql(bad))


if __name__ == "__main__":
    unittest.main()
