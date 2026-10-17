#!/usr/bin/env python3
"""P07 API smoke: the ledger through the gateway (:8080) with real demo tokens. Create with an Idempotency-Key and
replay it, list paging (keyset cursor), GET with ETag, PATCH with a stale (412) / missing (428) / current If-Match,
daily totals move with every write, soft delete (idempotent) + :restore, cross-tenant 404, categories (system list,
custom create, 409 on a duplicate). Prints PASS/FAIL lines; exit 1 on any failure.

Usage: p07_smoke.py http://localhost:8080
Env: DEMO_PASSWORD_ASHA, DEMO_PASSWORD_RAVI.
"""
import datetime
import os
import random
import sys
import uuid

sys.path.insert(0, os.path.dirname(__file__))
if len(sys.argv) < 3:
    sys.argv.append("http://127.0.0.1:8025")  # p04_smoke reads a Mailpit URL at import; P07 sends no mail
import p04_smoke as p4  # noqa: E402

GW = sys.argv[1].rstrip("/")
call, check, code, bearer = p4.call, p4.check, p4.code, p4.bearer
IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))


def login(email, pw):
    s, h, t = call("POST", GW + "/v1/auth/login", {"email": email, "password": pw},
                   {"X-Forwarded-For": "198.51.100.%d" % random.randint(1, 254)})
    return (t or {}).get("accessToken") if s == 200 else None


def totals(tok, day):
    s, h, d = call("GET", f"{GW}/v1/expenses/daily-totals?from={day}&to={day}", headers=bearer(tok))
    rows = (d or {}).get("data", []) if s == 200 else []
    return s, sum(r["totalPaise"] for r in rows), sum(r["txnCount"] for r in rows), rows


def main():
    pa, pr = os.environ.get("DEMO_PASSWORD_ASHA"), os.environ.get("DEMO_PASSWORD_RAVI")
    if not check("DEMO_PASSWORD_ASHA / _RAVI set", bool(pa and pr)):
        return
    asha, ravi = login("asha@demo.in", pa), login("ravi@demo.in", pr)
    if not check("asha + ravi sign in through the gateway", bool(asha and ravi)):
        return

    # ---- list + keyset paging over the seeded rows
    s, h, d = call("GET", GW + "/v1/expenses?limit=50", headers=bearer(asha))
    page = (d or {}).get("page", {})
    check("list: 200, 50 rows, hasMore, opaque cursor", s == 200 and len(d.get("data", [])) == 50 and page.get("hasMore")
          and page.get("nextCursor"), (s, page))
    first_ids = [r["id"] for r in (d or {}).get("data", [])]
    s, h, d2 = call("GET", GW + "/v1/expenses?limit=25&cursor=" + str(page.get("nextCursor")), headers=bearer(asha))
    nxt = (d2 or {}).get("data", [])
    check("next page: 25 rows, none repeated, still newest-first",
          s == 200 and len(nxt) == 25 and not set(first_ids) & {r["id"] for r in nxt}
          and all(nxt[i]["occurredAt"] >= nxt[i + 1]["occurredAt"] for i in range(len(nxt) - 1)), (s, d2))
    s, h, d3 = call("GET", GW + "/v1/expenses?limit=25&category=food&cursor=" + str(page.get("nextCursor")),
                    headers=bearer(asha))
    check("cursor with other filters: 400 CURSOR_FILTER_MISMATCH", s == 400 and code(d3) == "CURSOR_FILTER_MISMATCH", (s, d3))
    s, h, d3 = call("GET", GW + "/v1/expenses?limit=20&category=food", headers=bearer(asha))
    check("category filter: subtree only", s == 200 and all(r["categoryPath"] == "food" or r["categoryPath"].startswith("food.")
                                                          for r in d3.get("data", [])), (s, d3))

    # ---- create + replay, totals move
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    day = now.astimezone(IST).date().isoformat()
    _, sum0, n0, _ = totals(asha, day)
    key = str(uuid.uuid4())
    body = {"amountPaise": 34900, "occurredAt": now.isoformat().replace("+00:00", "Z"), "merchant": "Smoke Cafe",
            "categoryPath": "food.cafe", "paymentMethod": "upi", "note": "p07 smoke", "tags": ["smoke"]}
    s, h, e = call("POST", GW + "/v1/expenses", body, bearer(asha, **{"Idempotency-Key": key}))
    ok = check("create: 201, Location, ETag \"0\", merchant echoed",
               s == 201 and h.get("location") == "/v1/expenses/" + str((e or {}).get("id")) and h.get("etag") == '"0"'
               and e.get("merchant") == "Smoke Cafe" and e.get("version") == 0, (s, h, e))
    if not ok:
        return
    eid = e["id"]
    s, h, r = call("POST", GW + "/v1/expenses", body, bearer(asha, **{"Idempotency-Key": key}))
    check("same key again: replayed, same id", s == 201 and h.get("idempotent-replayed") == "true" and r.get("id") == eid, (s, h, r))
    s, h, r = call("POST", GW + "/v1/expenses", dict(body, amountPaise=35000), bearer(asha, **{"Idempotency-Key": key}))
    check("same key, other body: 422 IDEMPOTENCY_KEY_REUSED", s == 422 and code(r) == "IDEMPOTENCY_KEY_REUSED", (s, r))
    s, h, r = call("POST", GW + "/v1/expenses", body, bearer(asha))
    check("no key: 400 IDEMPOTENCY_KEY_REQUIRED", s == 400 and code(r) == "IDEMPOTENCY_KEY_REQUIRED", (s, r))
    s, sum1, n1, _ = totals(asha, day)
    check("daily totals: +₹349, +1 txn after create", s == 200 and sum1 - sum0 == 34900 and n1 - n0 == 1, (s, sum0, sum1, n0, n1))

    # ---- get / patch with ETag
    s, h, g = call("GET", GW + "/v1/expenses/" + eid, headers=bearer(asha))
    check("get: 200 + ETag \"0\"", s == 200 and h.get("etag") == '"0"', (s, h))
    mp = "application/merge-patch+json"
    s, h, r = call("PATCH", GW + "/v1/expenses/" + eid, {"amountPaise": 35000}, bearer(asha, **{"If-Match": '"7"'}), content_type=mp)
    check("patch with a stale If-Match: 412 VERSION_CONFLICT", s == 412 and code(r) == "VERSION_CONFLICT", (s, r))
    s, h, r = call("PATCH", GW + "/v1/expenses/" + eid, {"amountPaise": 35000}, bearer(asha), content_type=mp)
    check("patch without If-Match: 428 IF_MATCH_REQUIRED", s == 428 and code(r) == "IF_MATCH_REQUIRED", (s, r))
    s, h, r = call("PATCH", GW + "/v1/expenses/" + eid, {"amountPaise": 35000, "note": None},
                   bearer(asha, **{"If-Match": '"0"'}), content_type=mp)
    check("patch with the current ETag: 200, version 1, note cleared",
          s == 200 and h.get("etag") == '"1"' and r.get("amountPaise") == 35000 and r.get("note") is None, (s, h, r))
    s, h, r = call("PATCH", GW + "/v1/expenses/" + eid, {"accountId": str(uuid.uuid4())}, bearer(asha, **{"If-Match": '"1"'}),
                   content_type=mp)
    check("patch of a non-patchable field: 400 UNKNOWN_FIELD", s == 400 and code(r) == "UNKNOWN_FIELD", (s, r))
    s, sum2, n2, _ = totals(asha, day)
    check("daily totals follow the patch (+₹1)", sum2 - sum1 == 100 and n2 == n1, (sum1, sum2))

    # ---- tenant isolation
    for m, b, hd in (("GET", None, {}), ("PATCH", {"note": "x"}, {"If-Match": '"1"'}), ("DELETE", None, {})):
        s, h, r = call(m, GW + "/v1/expenses/" + eid, b, bearer(ravi, **hd), content_type=mp)
        check(f"ravi {m} asha's expense: 404 (RLS, never 403)", s == 404 and code(r) == "NOT_FOUND", (s, r))
    s, h, r = call("GET", GW + "/v1/expenses?limit=100", headers=bearer(ravi))
    check("ravi's list never contains asha's expense", s == 200 and eid not in {x["id"] for x in r.get("data", [])}, (s,))

    # ---- delete / restore
    s1, _, _ = call("DELETE", GW + "/v1/expenses/" + eid, headers=bearer(asha))
    s2, _, _ = call("DELETE", GW + "/v1/expenses/" + eid, headers=bearer(asha))
    s3, _, r = call("GET", GW + "/v1/expenses/" + eid, headers=bearer(asha))
    check("delete: 204, again 204 (idempotent), then GET 404", (s1, s2, s3) == (204, 204, 404), (s1, s2, s3, r))
    _, sum3, n3, _ = totals(asha, day)
    check("daily totals drop back after delete", sum3 == sum0 and n3 == n0, (sum0, sum3, n0, n3))
    s, h, r = call("POST", GW + "/v1/expenses/" + eid + ":restore", headers=bearer(asha))
    check("restore: 200, version 3", s == 200 and r.get("version") == 3 and h.get("etag") == '"3"', (s, h, r))
    _, sum4, n4, _ = totals(asha, day)
    check("daily totals back after restore", sum4 == sum2 and n4 == n2, (sum2, sum4))
    s, h, r = call("GET", f"{GW}/v1/expenses/daily-totals?from=2025-01-01&to=2026-12-31", headers=bearer(asha))
    check("daily totals over 366 days: 400 VALIDATION_FAILED", s == 400 and code(r) == "VALIDATION_FAILED", (s, r))

    # ---- categories
    s, h, r = call("GET", GW + "/v1/categories", headers=bearer(asha))
    cats = (r or {}).get("data", [])
    check("categories: the 48 system categories visible", s == 200 and sum(1 for c in cats if c.get("system")) == 48, (s, len(cats)))
    name = "Smoke " + uuid.uuid4().hex[:6]
    s, h, c = call("POST", GW + "/v1/categories", {"parentPath": "food", "name": name}, bearer(asha))
    check("custom category: 201 under food", s == 201 and (c or {}).get("path", "").startswith("food.") and not c.get("system"), (s, c))
    s, h, r = call("POST", GW + "/v1/categories", {"parentPath": "food", "name": name}, bearer(asha))
    check("same category again: 409 CATEGORY_EXISTS", s == 409 and code(r) == "CATEGORY_EXISTS", (s, r))
    s, h, r = call("GET", GW + "/v1/categories", headers=bearer(ravi))
    check("ravi does not see asha's custom category",
          s == 200 and (c or {}).get("path") not in {x["path"] for x in r.get("data", [])}, (s,))


if __name__ == "__main__":
    main()
    print(f"# P07 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
