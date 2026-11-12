#!/usr/bin/env python3
"""P18 API smoke: audit, notifications and the privacy saga through the gateway (:8080).

The P15 lesson is the design rule: an identity asserted over an empty collection is not a test. Nothing here is read
from seeded state. Two accounts are REGISTERED by this script and given data in EVERY participating service through
the services' own APIs (an expense, a card, a budget it crosses, a PREMIUM checkout, a card application); every
service is asserted to hold rows for both accounts (NOT EMPTY, read with each service's own privacy_remaining)
before anything is exported or deleted. The parts that travel are waited for, never assumed: the events
(identity, planning, billing, commerce -> Kafka -> audit) before the inbox, the mock transport's refused first
attempt and its retry before "sent", every service's acknowledgement (Kafka both ways) before an export or a
deletion is called complete.

  two accounts with data in all seven services -> notifications in-app and by email (retried once, then sent) ->
  preferences with If-Match -> export A (7 parts + audit's, zipped, a presigned link) -> delete A (freeze, erase,
  shred, local) -> every service holds 0 rows of A and still holds B's -> A cannot sign in, its key is shredded ->
  the log names only a pseudonym -> the chain verifies, anchors exist -> the activity dashboard is not empty.

Usage: p18_smoke.py http://localhost:8080 http://127.0.0.1:8025
       p18_smoke.py --setup <gateway> <mailpit>   (prints a fresh account with notifications, as JSON; trail-check)
Env: P18_PSQL (a psql command line to the stack's Postgres as the superuser, e.g. "docker compose ... exec -T
     postgres psql -U postgres"), DEMO_PASSWORD_ADMIN + .run/admin-totp (the operator).
"""
import io
import json
import os
import random
import shlex
import subprocess
import sys
import time
import urllib.request
import uuid
import zipfile

sys.path.insert(0, os.path.dirname(__file__))
SETUP_ONLY = len(sys.argv) > 1 and sys.argv[1] == "--setup"
if SETUP_ONLY:
    sys.argv.pop(1)
if len(sys.argv) < 3:
    sys.argv.append("http://127.0.0.1:8025")
import p04_smoke as p4  # noqa: E402
import p10_smoke as p10  # noqa: E402
import p16_smoke as p16  # noqa: E402

GW = sys.argv[1].rstrip("/")
call, check, code, bearer = p4.call, p4.check, p4.code, p4.bearer
SLUG = "mock-saffron-millennia-cashback"
SERVICES = ["identity", "ledger", "portfolio", "planning", "reco", "billing", "commerce"]
PSQL = shlex.split(os.environ.get("P18_PSQL", "psql -h 127.0.0.1 -U postgres"))


def sql(db, query):
    """One value from the stack's Postgres (superuser), or None."""
    try:
        out = subprocess.run(PSQL + ["-d", db, "-tA", "-c", query], capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return None
    v = out.stdout.strip()
    return v if out.returncode == 0 and v != "" else None


def remaining(svc, account):
    v = sql(svc, f"SELECT {svc}.privacy_remaining('{account}')")
    return int(v) if v is not None and v.lstrip("-").isdigit() else None


def key(tok):
    return bearer(tok, **{"Idempotency-Key": str(uuid.uuid4())})


def login(acct):
    s, _, t = call("POST", GW + "/v1/auth/login", {"email": acct["email"], "password": p16.PASSWORD}, p16.ip())
    return t["accessToken"] if s == 200 else None


def wait(predicate, seconds=60, step=1.0):
    return p16.wait(predicate, seconds, step)


def populate(acct, card, label):
    """Data in every participating service, through each service's own API."""
    tok = acct["access"]
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    month = time.strftime("%Y-%m-01", time.gmtime(time.time() + 19800))
    s, _, b = call("POST", GW + "/v1/budgets", {"name": "Smoke P18", "period": "monthly", "categoryPath": "food",
                                                "limitPaise": 60000, "startsOn": month}, key(tok))
    check(f"{label}: planning budget (₹600 on food): 201", s == 201, (s, b))
    s, _, e = call("POST", GW + "/v1/expenses", {"amountPaise": 50000, "occurredAt": now, "merchant": "Smoke P18",
                                                 "categoryPath": "food.delivery", "paymentMethod": "upi"}, key(tok))
    check(f"{label}: ledger expense (₹500, crosses 50 % and 80 % of the budget): 201", s == 201, (s, e))
    s, _, c = call("POST", GW + "/v1/cards", {"cardProductId": card, "last4": "5%03d" % (int(time.time()) % 1000),
                                              "statementDay": 5, "dueOffsetDays": 20, "nickname": "Smoke P18"},
                   key(tok))
    check(f"{label}: portfolio card: 201", s == 201, (s, c))
    s, _, d, _ = p16.checkout(tok, "PREMIUM", "pm_mock_success", "KA")
    check(f"{label}: billing PREMIUM checkout: 2xx", s in (200, 201, 202), (s, d))
    s, _, a = call("POST", GW + "/v1/card-applications", {"cardProductId": card}, key(tok))
    check(f"{label}: commerce card application: 201", s == 201, (s, a))


def inbox(tok, query=""):
    s, _, d = call("GET", GW + "/v1/notifications" + query, headers=bearer(tok))
    return s, d if isinstance(d, dict) else {}


def setup():
    """For trail-check: a fresh account whose inbox is not empty (a budget it crossed), as JSON."""
    acct = p16.fresh_account("trailp18")
    tok = acct["access"]
    month = time.strftime("%Y-%m-01", time.gmtime(time.time() + 19800))
    call("POST", GW + "/v1/budgets", {"name": "Trail P18", "period": "monthly", "categoryPath": "food",
                                      "limitPaise": 60000, "startsOn": month}, key(tok))
    call("POST", GW + "/v1/expenses", {"amountPaise": 50000, "occurredAt": time.strftime("%Y-%m-%dT%H:%M:%SZ",
                                                                                          time.gmtime()),
                                       "merchant": "Trail P18", "categoryPath": "food.delivery",
                                       "paymentMethod": "upi"}, key(tok))
    wait(lambda: len(inbox(tok)[1].get("data") or []) > 0, seconds=60)
    return {"access": tok, "account": acct["account"], "email": acct["email"]}


def main():
    s, _, d = call("GET", GW + "/v1/notifications")
    check("notifications without a token: 401 at the edge", s == 401, (s, d))
    if not check("P18_PSQL reaches the stack's Postgres", sql("audit", "SELECT 1") == "1", PSQL):
        return
    admin = p10.admin_token()
    if not check("operator signs in with MFA (SUPPORT carries audit:read)", admin is not None
                 and "audit:read" in p16.claims(admin).get("perms", [])):
        return
    s, _, product = call("GET", GW + "/v1/catalog/cards/" + SLUG)
    if not check("the P10 catalog card is public", s == 200, (s, product)):
        return
    card = product["id"]

    # ---------------------------------------------------------------- two accounts, data in every service
    a, b = p16.fresh_account("smokep18a"), p16.fresh_account("smokep18b")
    populate(a, card, "A")
    populate(b, card, "B")
    # identity: a phone, sealed under the account's own data key (the key the deletion must shred)
    s, h, me = call("GET", GW + "/v1/me", headers=bearer(a["access"]))
    phone = "+91 9%09d" % random.randint(0, 999_999_999)
    s, _, me = call("PATCH", GW + "/v1/me", {"phone": phone}, bearer(a["access"], **{"If-Match": h.get("etag", '"0"')}))
    check("A: identity phone saved (sealed under A's data key): 200", s == 200 and (me or {}).get("phone"), (s, me))
    check("identity: A's data key exists and is live before the deletion (NOT EMPTY)",
          sql("identity", f"SELECT count(*) FROM identity.account_keys WHERE account_id = '{a['account']}'"
                          " AND destroyed_at IS NULL AND length(dek_enc) > 0") == "1")
    for svc in SERVICES:
        ok = wait(lambda: (remaining(svc, a["account"]) or 0) > 0 and (remaining(svc, b["account"]) or 0) > 0,
                  seconds=60)
        check(f"{svc}: holds rows for A and B before anything is deleted (NOT EMPTY)", ok,
              (remaining(svc, a["account"]), remaining(svc, b["account"])))
    before_b = {svc: remaining(svc, b["account"]) for svc in SERVICES}

    # ---------------------------------------------------------------- notifications from four producers' events
    want = {"budget.threshold", "billing.subscription", "commerce.application"}
    got = wait(lambda: (lambda items: items if want <= {x["template"] for x in items} else None)(
        inbox(b["access"])[1].get("data") or []), seconds=90)
    check("B's inbox: NOT EMPTY, notified by planning (budget), billing (subscription) and commerce (application) "
          "over Kafka", got is not None, [x["template"] for x in (inbox(b["access"])[1].get("data") or [])])
    s, box = inbox(b["access"])
    items = box.get("data") or []
    check("inbox: newest first, rendered texts, an unread count", s == 200 and items and box.get("unread", 0) >= 3
          and all(x.get("title") for x in items) and [x["createdAt"] for x in items] == sorted(
              [x["createdAt"] for x in items], reverse=True), box)
    budget_item = next((x for x in items if x["template"] == "budget.threshold"), None)
    check("budget notice says rupees", budget_item is not None and "₹" in budget_item["body"], budget_item)
    if items:
        s, _, r = call("POST", GW + f"/v1/notifications/{items[0]['id']}:read", headers=bearer(b["access"]))
        check("mark one read: 200, readAt set", s == 200 and r.get("readAt"), (s, r))
        s, _, r = call("POST", GW + f"/v1/notifications/{items[0]['id']}:read", headers=bearer(a["access"]))
        check("someone else's notification: 404 NOTIFICATION_NOT_FOUND", s == 404
              and code(r) == "NOTIFICATION_NOT_FOUND", (s, r))
    retried = wait(lambda: sql("audit", "SELECT count(*) FROM audit.notifications WHERE account_id = '"
                               + b["account"] + "' AND channel = 'email' AND status = 'sent' AND attempts = 2"
                               " AND sent_at - created_at >= interval '2 seconds'") not in (None, "0"), seconds=60)
    check("email: the mock transport refused the first attempt, the retry 2 s later sent it (attempts = 2)",
          retried, sql("audit", "SELECT string_agg(status || '/' || attempts, ',') FROM audit.notifications"
                                " WHERE account_id = '" + b["account"] + "' AND channel = 'email'"))
    s, h, p = call("GET", GW + "/v1/notification-prefs", headers=bearer(b["access"]))
    check("prefs: defaults, version 0", s == 200 and p.get("version") == 0 and h.get("etag") == '"0"', (s, p))
    s, _, p = call("PATCH", GW + "/v1/notification-prefs", {"muted": ["security"]},
                   bearer(b["access"], **{"If-Match": '"0"'}))
    check("prefs: security cannot be muted (400)", s == 400, (s, p))
    s, _, p = call("PATCH", GW + "/v1/notification-prefs", {"muted": ["commerce"], "push": False},
                   bearer(b["access"], **{"If-Match": '"0"'}))
    check("prefs: PATCH with If-Match -> version 1", s == 200 and p.get("version") == 1 and p.get("muted") == [
        "commerce"], (s, p))

    # ---------------------------------------------------------------- export A
    tok_a = login(a)
    s, _, x = call("POST", GW + "/v1/privacy/export", headers=bearer(tok_a))
    check("export without an Idempotency-Key: refused", 400 <= s <= 428, (s, x))
    k = str(uuid.uuid4())
    s, _, x = call("POST", GW + "/v1/privacy/export", headers=bearer(tok_a, **{"Idempotency-Key": k}))
    if not check("export A: 202 (step-up: a fresh sign-in)", s == 202 and x.get("id"), (s, x)):
        return
    export_id = x["id"]
    s, _, again = call("POST", GW + "/v1/privacy/export", headers=bearer(tok_a, **{"Idempotency-Key": k}))
    check("export replayed with the same key: the same request", s == 202 and again.get("id") == export_id, again)
    s, _, other = call("POST", GW + "/v1/privacy/export", headers=key(tok_a))
    check("a second export while one runs: 409 PRIVACY_REQUEST_RUNNING", s == 409
          and code(other) == "PRIVACY_REQUEST_RUNNING", (s, other))
    done = wait(lambda: (lambda r: r if r.get("status") != "running" else None)(
        call("GET", GW + f"/v1/privacy/requests/{export_id}", headers=bearer(tok_a))[2] or {}), seconds=180)
    check("export completed: every service answered over Kafka", (done or {}).get("status") == "completed", done)
    files = {}
    if done and done.get("downloadUrl"):
        try:
            with urllib.request.urlopen(done["downloadUrl"], timeout=30) as r:
                z = zipfile.ZipFile(io.BytesIO(r.read()))
                files = {n: z.read(n).decode() for n in z.namelist()}
        except Exception as e:  # noqa: BLE001
            files = {"error": str(e)}
    check("the presigned link (24 h) downloads one zip: 7 parts + audit.json + manifest.json",
          set(files) == {f"{s_}.json" for s_ in SERVICES} | {"audit.json", "manifest.json"}, list(files))
    for svc in SERVICES:
        part = json.loads(files.get(f"{svc}.json", "{}"))
        rows = sum(len(v) for v in part.values() if isinstance(v, list))
        check(f"export part {svc}: NOT EMPTY ({rows} rows of A), nothing of B, no secrets",
              rows > 0 and b["account"] not in files.get(f"{svc}.json", "")
              and "password_hash" not in files.get(f"{svc}.json", "") and "dek_enc" not in files.get(f"{svc}.json", ""),
              (rows, list(part)))

    # ---------------------------------------------------------------- delete A
    tok_a = login(a) or tok_a
    s, _, x = call("POST", GW + "/v1/privacy/delete", headers=key(tok_a))
    if not check("delete A: 202", s == 202 and x.get("id"), (s, x)):
        return
    delete_id = x["id"]
    done = wait(lambda: (lambda r: r if r.get("status") not in (None, "running") else None)(
        call("GET", GW + f"/v1/admin/audit/privacy-requests/{delete_id}", headers=bearer(admin))[2] or {}),
        seconds=240, step=2)
    check("deletion completed: freeze -> erase -> shred -> local, over Kafka", (done or {}).get("status") == "completed"
          and all(ph in (done or {}).get("steps", {}) for ph in ("freeze", "erase", "shred", "local")), done)
    check("the request is pseudonymous now: no account id, a subject reference", done is not None
          and done.get("accountId") is None and len(done.get("subjectRef") or "") == 64, done)
    for svc in SERVICES + ["audit"]:
        r = remaining(svc, a["account"])
        check(f"{svc}: holds 0 rows of A afterwards", r == 0, r)
    for svc in SERVICES:
        r = remaining(svc, b["account"])
        check(f"{svc}: B untouched ({r} rows, was {before_b[svc]})", r is not None and r >= before_b[svc] > 0, r)
    s, _, t = call("POST", GW + "/v1/auth/login", {"email": a["email"], "password": p16.PASSWORD}, p16.ip())
    check("A can no longer sign in", s in (400, 401, 403), (s, t))
    check("identity: A is a tombstone, its data key shredded",
          sql("identity", f"SELECT status || '|' || (email LIKE '%@deleted.invalid') || '|' || (SELECT length(dek_enc)"
                          f" || '/' || (destroyed_at IS NOT NULL) FROM identity.account_keys WHERE account_id ="
                          f" '{a['account']}') FROM identity.accounts WHERE id = '{a['account']}'") == "deleted|true|0/true",
          sql("identity", f"SELECT status || '|' || email FROM identity.accounts WHERE id = '{a['account']}'"))
    check("billing: A's invoices kept but no longer A's (re-keyed), its subscription ended",
          sql("billing", f"SELECT count(*) FROM billing.invoices WHERE account_id = '{a['account']}'") == "0"
          and int(sql("billing", "SELECT count(*) FROM billing.subscriptions WHERE status = 'canceled'"
                                 " AND canceled_at > now() - interval '10 minutes'") or 0) > 0)

    # ---------------------------------------------------------------- the log, the chain, anchors, activity
    s, _, log = call("GET", GW + "/v1/admin/audit?action=privacy.delete.completed&limit=5", headers=bearer(admin))
    entries = (log or {}).get("data") or []
    check("audit log: privacy.delete.completed NOT EMPTY, naming only the pseudonym", s == 200 and entries
          and entries[0]["targetType"] == "subject" and entries[0]["targetId"] == (done or {}).get("subjectRef")
          and a["account"] not in json.dumps(entries[0]), entries[:1])
    s, _, log = call("GET", GW + "/v1/admin/audit?limit=50", headers=bearer(admin))
    actions = {e["action"] for e in (log or {}).get("data") or []}
    check("audit log: NOT EMPTY, entries from identity's events and the privacy saga", s == 200
          and any(x.startswith("identity.") for x in actions) and any(x.startswith("privacy.") for x in actions),
          sorted(actions))
    s, _, v = call("POST", GW + "/v1/admin/audit:verify-chain", headers=bearer(admin))
    check("verify-chain: ok over a NOT EMPTY chain", s == 200 and v.get("ok") is True and v.get("checked", 0) > 0,
          (s, v))
    anchors = wait(lambda: int(sql("audit", "SELECT count(*) FROM audit.audit_anchors WHERE object_key IS NOT NULL")
                                or 0) > 0, seconds=90, step=3)
    check("anchor job: the head anchored and exported (every minute in dev)", anchors)
    s, _, dash = call("GET", GW + "/v1/admin/activity/dashboard?hours=2", headers=bearer(admin))
    check("activity dashboard: NOT EMPTY (the gateway's sampled requests, hourly)", s == 200
          and (dash or {}).get("totals", {}).get("requests", 0) > 0 and (dash or {}).get("services"), dash)
    s, _, d = call("GET", GW + "/v1/admin/audit", headers=bearer(b["access"]))
    check("a user token on the admin log: 403", s == 403, (s, d))


if __name__ == "__main__":
    if SETUP_ONLY:
        print(json.dumps(setup()))
        sys.exit(0)
    main()
    print(f"# P18 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
