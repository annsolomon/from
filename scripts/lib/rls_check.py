#!/usr/bin/env python3
"""Static RLS gate (MASTER_PLAN 3.2, 10: verify.sh static).

For every Flyway migration set under services/*/src/main/resources/db/migration:
  * every CREATE TABLE (except `PARTITION OF` children) must have
      ALTER TABLE <t> ENABLE ROW LEVEL SECURITY
      ALTER TABLE <t> FORCE  ROW LEVEL SECURITY
      at least one CREATE POLICY ... ON <t>
  * a table with an `account_id` column (tenant table) must have at least one policy that
    references `app_account()`.
  * exemption: a line `-- rls:exempt <table> <reason>` anywhere in the service's migrations
    (for example outbox/inbox, which are guarded by grants instead, MASTER_PLAN 6.3).
Usage: rls_check.py [services_dir ...]   exit 0 = pass, 1 = violations.
"""
import pathlib
import re
import sys

CREATE_RE = re.compile(
    r"\bCREATE\s+(?:UNLOGGED\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([\w.\"]+)\s*(PARTITION\s+OF\b|\()",
    re.I)
EXEMPT_RE = re.compile(r"--\s*rls:exempt\s+([\w.\"]+)\s+(\S.*)$", re.I | re.M)


def norm(name: str) -> str:
    return name.replace('"', "").lower().split(".")[-1]


def strip_comments(sql: str) -> str:
    sql = re.sub(r"/\*.*?\*/", " ", sql, flags=re.S)
    return re.sub(r"--[^\n]*", " ", sql)


def paren_body(sql: str, open_idx: int) -> str:
    depth = 0
    for i in range(open_idx, len(sql)):
        if sql[i] == "(":
            depth += 1
        elif sql[i] == ")":
            depth -= 1
            if depth == 0:
                return sql[open_idx + 1:i]
    return sql[open_idx + 1:]


def check_sql(raw: str) -> list:
    exempt = {norm(m.group(1)) for m in EXEMPT_RE.finditer(raw)}
    sql = strip_comments(raw)
    errors = []
    for m in CREATE_RE.finditer(sql):
        table = norm(m.group(1))
        if m.group(2).upper().startswith("PARTITION"):
            continue
        body = paren_body(sql, m.end() - 1)
        if table in exempt:
            continue
        t = re.escape(table)
        alter = r"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?(?:[\w\"]+\.)?\"?" + t + r"\"?\s+"
        if not re.search(alter + r"ENABLE\s+ROW\s+LEVEL\s+SECURITY", sql, re.I):
            errors.append(f"{table}: missing ENABLE ROW LEVEL SECURITY")
        if not re.search(alter + r"FORCE\s+ROW\s+LEVEL\s+SECURITY", sql, re.I):
            errors.append(f"{table}: missing FORCE ROW LEVEL SECURITY")
        pol_re = re.compile(
            r"CREATE\s+POLICY\s+[\w\"]+\s+ON\s+(?:[\w\"]+\.)?\"?" + t + r"\"?\b(.*?);", re.I | re.S)
        policies = [p.group(1) for p in pol_re.finditer(sql)]
        if not policies:
            errors.append(f"{table}: no CREATE POLICY")
        is_tenant = re.search(r"(^|,)\s*account_id\s+uuid\b", body, re.I | re.M) is not None
        if is_tenant and not any("app_account()" in p for p in policies):
            errors.append(f"{table}: tenant table (account_id) but no policy uses app_account()")
    return errors


def migration_sets(dirs):
    for d in dirs:
        for mig in sorted(pathlib.Path(d).glob("*/src/main/resources/db/migration")):
            files = sorted(mig.glob("*.sql"))
            if files:
                yield mig.parts[-6] if len(mig.parts) >= 6 else str(mig), files


def main(argv):
    dirs = argv[1:] or ["services"]
    failed = False
    checked = 0
    for svc, files in migration_sets(dirs):
        raw = "\n".join(f.read_text(encoding="utf-8") for f in files)
        checked += 1
        for e in check_sql(raw):
            failed = True
            print(f"RLS FAIL [{svc}] {e}")
    print(f"rls_check: {checked} service migration set(s) checked, {'FAIL' if failed else 'OK'}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
