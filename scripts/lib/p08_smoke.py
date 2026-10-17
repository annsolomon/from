#!/usr/bin/env python3
"""P08 API smoke: ledger B through the gateway (:8080) with a real demo token, on the seed-small data (merchants,
aliases, Asha's expenses). Autocomplete, resolver + rules, search paging, bulk create (partial failure, duplicates,
replay), CSV import through a presigned upload to the real object store (SeaweedFS) with re-import idempotency,
calendar and summary. Prints PASS/FAIL lines; exit 1 on any failure.

Usage: p08_smoke.py http://localhost:8080
Env: DEMO_PASSWORD_ASHA.
"""
import datetime
import os
import random
import sys
import time
import urllib.parse
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(__file__))
if len(sys.argv) < 3:
    sys.argv.append("http://127.0.0.1:8025")  # p04_smoke reads a Mailpit URL at import; P08 sends no mail
import p04_smoke as p4  # noqa: E402

GW = sys.argv[1].rstrip("/")
call, check, code, bearer = p4.call, p4.check, p4.code, p4.bearer
IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
HEADER = "date,amount,merchant,category,payment_method,note,client_ref"


def login(email, pw):
    s, h, t = call("POST", GW + "/v1/auth/login", {"email": email, "password": pw},
                   {"X-Forwarded-For": "198.51.100.%d" % random.randint(1, 254)})
    return (t or {}).get("accessToken") if s == 200 else None


def put(url, data, headers):
    req = urllib.request.Request(url, data=data, method="PUT", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception as e:  # noqa: BLE001
        return "transport error " + str(e)


def run_import(tok, csv):
    s, h, job = call("POST", GW + "/v1/imports", {"fileName": "smoke.csv", "sizeBytes": len(csv)}, bearer(tok))
    if s != 201:
        return s, job, None
    up = job["upload"]
    ps = put(up["url"], csv, up.get("headers") or {})
    s2, _, _ = call("POST", GW + "/v1/imports/" + job["id"] + ":start", None, bearer(tok))
    j = None
    for _ in range(120):
        time.sleep(0.5)
        _, _, j = call("GET", GW + "/v1/imports/" + job["id"], headers=bearer(tok))
        if (j or {}).get("status") in ("completed", "failed"):
            break
    return (201, ps, s2), job, j


def main():
    pa = os.environ.get("DEMO_PASSWORD_ASHA")
    if not check("DEMO_PASSWORD_ASHA set", bool(pa)):
        return
    tok = login("asha@demo.in", pa)
    if not check("asha signs in through the gateway", bool(tok)):
        return
    a = bearer(tok)

    # ---- autocomplete + resolver + rules (seed merchants loaded by seed-small)
    s, h, d = call("GET", GW + "/v1/merchants/autocomplete?q=swi", headers=a)
    names = [x.get("merchant") for x in (d or {}).get("data", [])]
    check("autocomplete q=swi: 200, ≤ 8, Swiggy first", s == 200 and 0 < len(names) <= 8 and names[0].startswith("Swiggy"),
          (s, d))
    s, h, d = call("GET", GW + "/v1/merchants/autocomplete?q=s", headers=a)
    check("autocomplete q=s: 400 VALIDATION_FAILED", s == 400 and code(d) == "VALIDATION_FAILED", (s, d))
    s, h, d = call("POST", GW + "/v1/merchants/resolve", {"merchant": "UPI/123412341234/ZOMATO/zomato12@ybl"}, a)
    r = (d or {}).get("resolution", {})
    check("resolve UPI descriptor: Zomato, food.delivery, exact, confidence ≥ 900",
          s == 200 and r.get("merchant") == "Zomato" and r.get("categoryPath") == "food.delivery"
          and r.get("method") == "exact" and r.get("confidence", 0) >= 900, (s, d))
    pattern = "smoketea" + uuid.uuid4().hex[:4]
    s, h, rule = call("POST", GW + "/v1/rules", {"matchType": "contains", "pattern": pattern, "categoryPath": "food.cafe",
                                               "priority": 5}, a)
    check("create rule: 201", s == 201 and (rule or {}).get("pattern") == pattern, (s, rule))
    s, h, d = call("POST", GW + "/v1/merchants/resolve", {"merchant": "Ganesh " + pattern + " stall"}, a)
    r = (d or {}).get("resolution", {})
    check("rule decides the category (source rule, confidence 1000)",
          s == 200 and r.get("categoryPath") == "food.cafe" and r.get("categorySource") == "rule", (s, d))
    if rule and rule.get("id"):
        s, _, _ = call("DELETE", GW + "/v1/rules/" + rule["id"], headers=a)
        check("delete rule: 204", s == 204, s)

    # ---- search on Asha's seeded expenses
    s, h, d = call("GET", GW + "/v1/expenses?limit=1", headers=a)
    latest = ((d or {}).get("data") or [{}])[0]
    word = next((w for w in (latest.get("merchant") or "").split() if len(w) >= 3), None)
    day = datetime.datetime.fromisoformat(latest.get("occurredAt", "2026-09-30T00:00:00Z").replace("Z", "+00:00")) \
        .astimezone(IST).date()
    if check("a seeded expense to search for", bool(word), latest):
        q = "/v1/expenses/search?q=%s&from=%s&to=%s&limit=5" % (urllib.parse.quote(word),
                                                              (day - datetime.timedelta(days=200)).isoformat(), day)
        s, h, d = call("GET", GW + q, headers=a)
        rows = (d or {}).get("data", [])
        check("search: 200, rows newest first", s == 200 and bool(rows)
              and rows == sorted(rows, key=lambda x: x["occurredAt"], reverse=True), (s, d))
        nxt = (d or {}).get("page", {}).get("nextCursor")
        if nxt:
            s, h, d2 = call("GET", GW + q + "&cursor=" + urllib.parse.quote(nxt), headers=a)
            ids = {x["id"] for x in rows}
            check("search next page: no repeats", s == 200 and not ids & {x["id"] for x in d2.get("data", [])}, (s, d2))
    s, h, d = call("GET", GW + "/v1/expenses/search?q=x", headers=a)
    check("search q too short: 400", s == 400 and code(d) == "VALIDATION_FAILED", (s, d))

    # ---- bulk
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    ref = "smoke-" + uuid.uuid4().hex[:8]
    items = [{"occurredAt": now, "amountPaise": 12000, "merchant": "ZOMATO", "paymentMethod": "upi", "clientRef": ref},
             {"occurredAt": now, "amountPaise": 9900, "merchant": "ZOMATO", "paymentMethod": "upi", "clientRef": ref},
             {"occurredAt": now, "amountPaise": 0, "merchant": "x", "paymentMethod": "cash"}]
    key = str(uuid.uuid4())
    s, h, b = call("POST", GW + "/v1/expenses:bulk", {"items": items}, bearer(tok, **{"Idempotency-Key": key}))
    check("bulk: 200, created 1 · duplicate 1 · invalid 1",
          s == 200 and (b or {}).get("created") == 1 and b.get("duplicates") == 1 and b.get("invalid") == 1, (s, b))
    s, h, b2 = call("POST", GW + "/v1/expenses:bulk", {"items": items}, bearer(tok, **{"Idempotency-Key": key}))
    check("bulk replay: same answer, Idempotent-Replayed", s == 200 and h.get("idempotent-replayed") == "true"
          and b2 == b, (s, h))
    if b and b.get("results"):
        s, h, e = call("GET", GW + "/v1/expenses/" + b["results"][0]["id"], headers=a)
        check("bulk item resolved: merchant Zomato, food.delivery",
              s == 200 and e.get("merchant") == "Zomato" and e.get("categoryPath") == "food.delivery", (s, e))

    # ---- CSV import through the object store
    today = datetime.datetime.now(IST).date()
    tag = uuid.uuid4().hex[:6]
    csv = (HEADER + "\n"
           + "%s,349.00,SWIGGY BANGALORE,,upi,smoke %s,\n" % (today.isoformat(), tag)
           + "%s,1250,POS 633108 VISTARA,,card,smoke %s,\n" % (today.strftime("%d/%m/%Y"), tag)
           + "2026-13-01,10,bad date,,upi,,\n").encode()
    st, job, j = run_import(tok, csv)
    check("import: create 201, PUT to the presigned URL 200, start 202", st == (201, 200, 202), (st, job))
    check("import completed: total 3, ok 2, failed 1 (row 3 DATE_INVALID)",
          (j or {}).get("status") == "completed" and j.get("total") == 3 and j.get("ok") == 2 and j.get("failed") == 1
          and j.get("errors", [{}])[0].get("code") == "DATE_INVALID", j)
    st, job2, j2 = run_import(tok, csv)
    check("re-import of the same file: completed, 2 duplicates, nothing new",
          (j2 or {}).get("status") == "completed" and j2.get("duplicates") == 2, j2)
    if job2 and job2.get("id"):
        s, h, d = call("POST", GW + "/v1/imports/" + job2["id"] + ":start", None, a)
        check("start a finished import: 409 IMPORT_NOT_STARTABLE", s == 409 and code(d) == "IMPORT_NOT_STARTABLE", (s, d))
    st, _, j3 = run_import(tok, b"when,how\n1,2\n")
    check("malformed CSV: job failed CSV_INVALID", (j3 or {}).get("status") == "failed"
          and j3.get("errorCode") == "CSV_INVALID", j3)

    # ---- calendar + summary
    month = today.strftime("%Y-%m")
    s, h, c = call("GET", GW + "/v1/calendar?month=" + month, headers=a)
    check("calendar: 200, one entry per day, today has spend",
          s == 200 and len(c.get("days", [])) >= 28 and any(x["day"] == today.isoformat() and x["spentPaise"] > 0
                                                          for x in c["days"]), (s, (c or {}).get("spentPaise")))
    frm = (today - datetime.timedelta(days=60)).isoformat()
    for by in ("category", "merchant", "paymentMethod", "day"):
        s, h, sm = call("GET", "%s/v1/summary?from=%s&to=%s&groupBy=%s" % (GW, frm, today.isoformat(), by), headers=a)
        check("summary groupBy=%s: 200, groups sum ≤ total" % by,
              s == 200 and sm.get("groups") and sum(g["totalPaise"] for g in sm["groups"]) <= sm["totalPaise"], (s, sm))


if __name__ == "__main__":
    main()
    print(f"# P08 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
