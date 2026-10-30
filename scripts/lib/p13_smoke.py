#!/usr/bin/env python3
"""P13 API smoke: planning through the gateway (:8080) as the demo users. Flow, on budgets in a category of this
run's own (so a budget created now starts at zero, ADR-0019 decision 3): create a budget (Idempotency-Key, replay,
validation, keyset page, ETag) -> record an expense in the LEDGER -> the budget-spend consumer applies the delta
through Kafka (a subtree match counted, a sibling category not, a thresholds alert once per period and an event on
the outbox) -> edit the expense (the delta settles both sides) -> delete it (the row floors at zero, never
negative) -> an expense at 23:30 IST on the last day of the month lands in that month -> a paused budget stops
tracking -> history -> end it (read-only afterwards) -> liabilities: Rs 1,00,000 at 12 % over 12 months is
Rs 8,884.88 a month (the old budget-service golden on the shared EMI engine), with the 12-row schedule summing
back to the principal -> close it -> scenarios: a copy of the account, a hypothetical car loan added, feasibility,
an adjustment the snapshot cannot satisfy refused, and the copy not following the account afterwards.
Isolation (ravi 404, anonymous 401) throughout. Prints PASS/FAIL; exit 1 on failure.

Usage: p13_smoke.py http://localhost:8080
Env: DEMO_PASSWORD_ASHA, DEMO_PASSWORD_RAVI.
"""
import datetime
import os
import random
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(__file__))
if len(sys.argv) < 3:
    sys.argv.append("http://127.0.0.1:8025")  # p04_smoke reads a Mailpit URL at import; P13 sends no mail
import p04_smoke as p4  # noqa: E402

GW = sys.argv[1].rstrip("/")
call, check, code, bearer = p4.call, p4.check, p4.code, p4.bearer
IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
# The ledger refuses a category that is not in its visible tree (UNKNOWN_CATEGORY), so these are seeded paths.
# `health` is the root no other smoke block writes to (P07/P08/P11 use food.* and travel.*), so the budget on it
# counts only this run's expenses; the no-category budget is asserted as a delta instead, because every expense on
# the account counts towards it.
ROOT = "health"
SUB = "health.doctor"
DEEPER = "health.pharmacy"
OTHER = "personal.salon"
LATE = "health.fitness"   # the month-end case: last month's day is outside the other budgets' windows anyway


def login(email, pw):
    s, h, t = call("POST", GW + "/v1/auth/login", {"email": email, "password": pw},
                   {"X-Forwarded-For": "198.51.100.%d" % random.randint(1, 254)})
    return (t or {}).get("accessToken") if s == 200 else None


def key(tok):
    return bearer(tok, **{"Idempotency-Key": str(uuid.uuid4())})


def today():
    return datetime.datetime.now(IST).date()


def budget(tok, **over):
    body = {"name": "Smoke", "period": "monthly", "categoryPath": ROOT, "limitPaise": 200_000,
            "startsOn": today().replace(day=1).isoformat()}
    body.update(over)
    s, h, d = call("POST", GW + "/v1/budgets", body, key(tok))
    return s, h, d


def expense(tok, amount, category, occurred=None):
    body = {"amountPaise": amount, "occurredAt": occurred or datetime.datetime.now(datetime.timezone.utc)
            .isoformat().replace("+00:00", "Z"), "merchant": "Smoke P13", "categoryPath": category,
            "paymentMethod": "upi"}
    s, h, d = call("POST", GW + "/v1/expenses", body, key(tok))
    return s, d


def status(tok, budget_id, want=None, seconds=30):
    """The budget's current period once `want` paise have landed (or after the wait); None if the budget has none."""
    end = time.time() + seconds
    row = None
    while True:
        s, _, d = call("GET", GW + "/v1/budgets/status", headers=bearer(tok))
        if s == 200 and isinstance(d, list):
            for r in d:
                if r["budget"]["id"] == budget_id:
                    row = r.get("current")
        if want is None or (row and row["spentPaise"] == want) or time.time() > end:
            return row
        time.sleep(0.5)


def main():
    pa, pr = os.environ.get("DEMO_PASSWORD_ASHA"), os.environ.get("DEMO_PASSWORD_RAVI")
    if not check("DEMO_PASSWORD_ASHA / _RAVI set", bool(pa and pr)):
        return
    asha, ravi = login("asha@demo.in", pa), login("ravi@demo.in", pr)
    if not check("asha + ravi sign in through the gateway", bool(asha and ravi)):
        return

    # ---- budgets: the edge, validation, idempotency, the page
    s, _, d = call("GET", GW + "/v1/budgets")
    check("budgets without a token: 401 at the edge", s == 401, (s, d))
    s, _, d = call("GET", GW + "/v1/budgets/status")
    check("status without a token: 401", s == 401, (s, d))

    s, _, d = call("POST", GW + "/v1/budgets", {"name": "No key", "period": "monthly", "limitPaise": 1000},
                   bearer(asha))
    check("a budget without an Idempotency-Key: 400", s == 400 and code(d) == "IDEMPOTENCY_KEY_REQUIRED", (s, d))
    s, _, d = budget(asha, period="fortnightly")
    check("an unknown period: 400 with the field named", s == 400 and code(d) == "VALIDATION_FAILED"
          and any(e["field"] == "period" for e in d.get("errors") or []), (s, d))
    s, _, d = budget(asha, limitPaise=0)
    check("a zero limit: 400", s == 400 and any(e["field"] == "limitPaise" for e in d.get("errors") or []), (s, d))
    s, _, d = budget(asha, categoryPath="Food Dining")
    check("a category that is not an ltree path: 400", s == 400
          and any(e["field"] == "categoryPath" for e in d.get("errors") or []), (s, d))
    s, _, d = budget(asha, period="custom", endsOn=None)
    check("a custom period without an end: 400", s == 400
          and any(e["field"] == "endsOn" for e in d.get("errors") or []), (s, d))
    s, _, d = budget(asha, alertThresholds=[10, 20, 30, 40, 50, 60])
    check("six thresholds: 400 TOO_MANY", s == 400
          and any(e["field"] == "alertThresholds" for e in d.get("errors") or []), (s, d))

    k = key(asha)
    body = {"name": "Smoke subtree", "period": "monthly", "categoryPath": ROOT, "limitPaise": 200_000,
            "startsOn": today().replace(day=1).isoformat()}
    s, h, b = call("POST", GW + "/v1/budgets", body, k)
    ok = s == 201 and b.get("status") == "active" and b.get("alertThresholds") == [50, 80, 100]
    check("a budget: 201, active, thresholds 50/80/100, ETag \"0\"", ok and h.get("etag") == '"0"'
          and h.get("location", "").endswith(b.get("id", "x")), (s, h, b))
    if not ok:
        return
    bid = b["id"]
    s2, h2, b2 = call("POST", GW + "/v1/budgets", body, k)
    check("the same key replays the same budget", s2 == 201 and b2.get("id") == bid
          and h2.get("idempotent-replayed") == "true", (s2, h2, b2))
    s, _, d = call("POST", GW + "/v1/budgets", dict(body, name="Different"), k)
    check("the same key with another body: 422 IDEMPOTENCY_KEY_REUSED", s == 422
          and code(d) == "IDEMPOTENCY_KEY_REUSED", (s, d))

    s, h, d = call("GET", GW + "/v1/budgets/" + bid, headers=bearer(asha))
    check("GET the budget: 200, ETag = version", s == 200 and h.get("etag") == '"0"' and d["id"] == bid, (s, h, d))
    s, _, d = call("GET", GW + "/v1/budgets/" + bid, headers=bearer(ravi))
    check("another account's budget: 404, not 403", s == 404 and code(d) == "NOT_FOUND", (s, d))
    s, _, d = call("GET", GW + "/v1/budgets?limit=1", headers=bearer(asha))
    check("budgets paginate by keyset", s == 200 and len(d.get("data") or []) == 1 and "page" in d
          and d["page"].get("limit") == 1, (s, d))
    s, _, d = call("GET", GW + "/v1/budgets?limit=1&cursor=not-a-cursor", headers=bearer(asha))
    check("a forged cursor: 400", s == 400 and (code(d) or "").startswith("CURSOR"), (s, d))

    cur = status(asha, bid)
    check("a budget created now starts at zero (no backfill)", cur and cur["spentPaise"] == 0
          and cur["usedBps"] == 0 and cur["periodStart"] == today().replace(day=1).isoformat(), cur)
    check("the period ends on the last day of the month", cur and cur["periodEnd"][:7] == cur["periodStart"][:7]
          and cur["daysInPeriod"] in (28, 29, 30, 31), cur)

    # a second budget with no category at all: every expense on the account counts towards it, including any from
    # an earlier smoke block still in flight, so this one is asserted as a delta from the moment it was created
    s, _, allspend = budget(asha, name="Smoke all spend", categoryPath=None, limitPaise=1_000_000)
    check("a budget with no category: 201, all spend", s == 201 and allspend.get("categoryPath") is None, (s, allspend))
    aid = allspend.get("id")
    base = (status(asha, aid) or {}).get("spentPaise", 0)

    # ---- the ledger -> Kafka -> the delta
    s, e1 = expense(asha, 60_000, SUB)
    if not check("an expense in the ledger: 201", s == 201, (s, e1)):
        return
    cur = status(asha, bid, want=60_000)
    check("a subtree expense counts: Rs 600 of Rs 2,000, 3000 bps", cur and cur["spentPaise"] == 60_000
          and cur["usedBps"] == 3000 and cur["remainingPaise"] == 140_000 and not cur["overLimit"], cur)
    check("no threshold crossed at 30 %", cur and cur["thresholdsCrossed"] == [], cur)
    check("the all-spend budget counted it too",
          (status(asha, aid, want=base + 60_000) or {}).get("spentPaise") == base + 60_000, status(asha, aid))

    s, e2 = expense(asha, 40_000, OTHER)
    check("an expense in another root: 201", s == 201, (s, e2))
    check("a sibling category does not count towards the subtree budget",
          (status(asha, aid, want=base + 100_000) or {}).get("spentPaise") == base + 100_000
          and (status(asha, bid) or {}).get("spentPaise") == 60_000,
          (status(asha, aid), status(asha, bid)))

    # cross 50 % and 80 % in one go: 600 -> 1,700
    s, e3 = expense(asha, 110_000, DEEPER)
    cur = status(asha, bid, want=170_000)
    check("another child of the same root counts: Rs 1,700, 8500 bps", s == 201 and cur
          and cur["spentPaise"] == 170_000 and cur["usedBps"] == 8500, (s, cur))
    check("50 % and 80 % crossed, 100 % not", cur and cur["thresholdsCrossed"] == [50, 80], cur)

    # over the limit, then another expense: no threshold alerts twice in a period
    s, e4 = expense(asha, 60_000, SUB)
    cur = status(asha, bid, want=230_000)
    check("over the limit: remaining is negative, overLimit true", cur and cur["remainingPaise"] == -30_000
          and cur["overLimit"] and cur["thresholdsCrossed"] == [50, 80, 100], cur)
    s, e5 = expense(asha, 10_000, SUB)
    cur = status(asha, bid, want=240_000)
    check("a threshold alerts once per period, not again", cur and cur["thresholdsCrossed"] == [50, 80, 100], cur)
    check("the projection is at least the spend", cur and cur["projectedSpendPaise"] >= cur["spentPaise"]
          and cur["projectedOverspendPaise"] == max(0, cur["projectedSpendPaise"] - cur["limitPaise"]), cur)

    # ---- an edit settles both sides, a delete floors at zero
    if not check("the expenses to edit and delete were created", bool(e5.get("id") and e4.get("id")), (e4, e5)):
        return
    s, h, _ = call("GET", GW + "/v1/expenses/" + e5["id"], headers=bearer(asha))
    s, _, d = call("PATCH", GW + "/v1/expenses/" + e5["id"], {"amountPaise": 30_000},
                   bearer(asha, **{"If-Match": h.get("etag", '"0"')}), content_type="application/merge-patch+json")
    cur = status(asha, bid, want=260_000)
    check("an edited amount settles both sides: Rs 2,600", s == 200 and cur and cur["spentPaise"] == 260_000,
          (s, cur))
    s, h, _ = call("GET", GW + "/v1/expenses/" + e5["id"], headers=bearer(asha))
    s, _, d = call("PATCH", GW + "/v1/expenses/" + e5["id"], {"categoryPath": OTHER},
                   bearer(asha, **{"If-Match": h.get("etag", '"1"')}), content_type="application/merge-patch+json")
    cur = status(asha, bid, want=230_000)
    check("moving an expense out of the category subtracts it", s == 200 and cur
          and cur["spentPaise"] == 230_000, (s, cur))
    s, _, _ = call("DELETE", GW + "/v1/expenses/" + e4["id"], headers=bearer(asha))
    cur = status(asha, bid, want=170_000)
    check("deleting an expense subtracts it", s == 204 and cur and cur["spentPaise"] == 170_000, (s, cur))
    check("spend never goes negative", (status(asha, bid) or {}).get("spentPaise", -1) >= 0, status(asha, bid))

    # ---- 23:30 IST on the last day of the month lands in that month (the ledger stamps the local day)
    first = today().replace(day=1)
    last_prev = first - datetime.timedelta(days=1)
    s, _, back = budget(asha, name="Smoke last day", categoryPath=LATE,
                        startsOn=last_prev.replace(day=1).isoformat(), limitPaise=500_000)
    if check("a budget covering last month: 201", s == 201, (s, back)):
        late = datetime.datetime.combine(last_prev, datetime.time(23, 30), IST)
        s, el = expense(asha, 50_000, LATE, occurred=late.astimezone(datetime.timezone.utc)
                        .isoformat().replace("+00:00", "Z"))
        # today is in the newer period, so last month's spend shows up in the history rather than in `current`
        end = time.time() + 30
        rows = []
        while time.time() < end:
            st, _, hd = call("GET", GW + "/v1/budgets/%s/history?limit=12" % back["id"], headers=bearer(asha))
            rows = (hd or {}).get("data") or []
            if any(r["spentPaise"] == 50_000 for r in rows):
                break
            time.sleep(0.5)
        hit = [r for r in rows if r["spentPaise"] == 50_000]
        check("23:30 IST on the %s counts in that month, not the next" % last_prev.isoformat(),
              s == 201 and len(hit) == 1 and hit[0]["periodStart"] == last_prev.replace(day=1).isoformat(),
              (s, rows))

    # ---- a paused budget stops tracking; an ended one is read-only
    before_pause = (status(asha, aid) or {}).get("spentPaise")
    s, h, _ = call("GET", GW + "/v1/budgets/" + aid, headers=bearer(asha))
    s, _, d = call("PATCH", GW + "/v1/budgets/" + aid, {"status": "paused"},
                   bearer(asha, **{"If-Match": h.get("etag")}), content_type="application/merge-patch+json")
    check("pausing a budget: 200", s == 200 and d.get("status") == "paused", (s, d))
    check("a paused budget is not in /status", status(asha, aid) is None, status(asha, aid))
    expense(asha, 20_000, OTHER)
    time.sleep(3)
    s, h, d = call("GET", GW + "/v1/budgets/" + aid, headers=bearer(asha))
    s, _, d = call("PATCH", GW + "/v1/budgets/" + aid, {"status": "active"},
                   bearer(asha, **{"If-Match": h.get("etag")}), content_type="application/merge-patch+json")
    resumed = (status(asha, aid) or {}).get("spentPaise")
    check("resuming it does not pick up the Rs 200 spent while it was paused", s == 200
          and resumed == before_pause, (s, before_pause, resumed))

    s, _, d = call("PATCH", GW + "/v1/budgets/" + bid, {"limitPaise": 300_000}, bearer(asha),
                   content_type="application/merge-patch+json")
    check("a patch without If-Match: 428", s == 428 and code(d) == "IF_MATCH_REQUIRED", (s, d))
    s, _, d = call("PATCH", GW + "/v1/budgets/" + bid, {"limitPaise": 300_000},
                   bearer(asha, **{"If-Match": '"99"'}), content_type="application/merge-patch+json")
    check("a stale If-Match: 412", s == 412 and code(d) == "VERSION_CONFLICT", (s, d))
    s, _, d = call("PATCH", GW + "/v1/budgets/" + bid, {"period": "weekly"},
                   bearer(asha, **{"If-Match": '"0"'}), content_type="application/merge-patch+json")
    check("the period is not patchable: 400 UNKNOWN_FIELD", s == 400
          and any(e["code"] == "UNKNOWN_FIELD" for e in d.get("errors") or []), (s, d))
    s, h, d = call("GET", GW + "/v1/budgets/" + bid, headers=bearer(asha))
    s, _, d = call("PATCH", GW + "/v1/budgets/" + bid, {"limitPaise": 300_000},
                   bearer(asha, **{"If-Match": h.get("etag")}), content_type="application/merge-patch+json")
    check("raising the limit: 200, the status follows", s == 200 and d["limitPaise"] == 300_000
          and (status(asha, bid) or {}).get("limitPaise") == 300_000, (s, d))

    s, _, d = call("GET", GW + "/v1/budgets/%s/history?limit=12" % bid, headers=bearer(asha))
    check("history is a keyset page of periods", s == 200 and isinstance(d.get("data"), list)
          and all({"periodStart", "spentPaise", "limitPaise"} <= set(r) for r in d["data"]), (s, d))

    s, _, _ = call("DELETE", GW + "/v1/budgets/" + bid, headers=bearer(asha))
    check("ending a budget: 204", s == 204, s)
    s, _, d = call("DELETE", GW + "/v1/budgets/" + bid, headers=bearer(asha))
    check("ending it again: 204 (idempotent)", s == 204, (s, d))
    check("an ended budget leaves /status", (status(asha, bid) or {}) == {}, status(asha, bid))
    s, h, d = call("GET", GW + "/v1/budgets/" + bid, headers=bearer(asha))
    check("it is still readable, with its history", s == 200 and d["status"] == "ended", (s, d))
    s, _, d = call("PATCH", GW + "/v1/budgets/" + bid, {"limitPaise": 1000},
                   bearer(asha, **{"If-Match": h.get("etag")}), content_type="application/merge-patch+json")
    check("an ended budget is read-only: 409 BUDGET_NOT_ACTIVE", s == 409 and code(d) == "BUDGET_NOT_ACTIVE",
          (s, d))

    # ---- liabilities on the shared EMI engine
    loan = {"kind": "loan", "lender": "Smoke Bank", "principalPaise": 10_000_000, "aprBps": 1200,
            "tenureMonths": 12, "startDate": "2026-11-01"}
    s, _, d = call("POST", GW + "/v1/liabilities", dict(loan, kind="mortgage"), key(asha))
    check("an unknown liability kind: 400", s == 400 and any(e["field"] == "kind" for e in d.get("errors") or []),
          (s, d))
    lk = key(asha)
    s, h, lia = call("POST", GW + "/v1/liabilities", loan, lk)
    ok = s == 201 and lia.get("emiPaise") == 888_488
    check("Rs 1,00,000 at 12 % over 12 months: Rs 8,884.88 a month (the old budget-service golden)", ok, (s, lia))
    check("interest Rs 6,618.53, payable Rs 1,06,618.53", lia.get("totalInterestPaise") == 661_853
          and lia.get("totalPayablePaise") == 10_661_853, lia)
    check("the last due date is the 12th instalment", lia.get("lastDueDate") == "2027-10-01", lia)
    if not ok:
        return
    lid = lia["id"]
    s2, h2, l2 = call("POST", GW + "/v1/liabilities", loan, lk)
    check("the same key replays the liability", s2 == 201 and l2.get("id") == lid
          and h2.get("idempotent-replayed") == "true", (s2, h2))
    s, _, d = call("GET", GW + "/v1/liabilities/%s/schedule?limit=24" % lid, headers=bearer(asha))
    rows = (d or {}).get("data") or []
    check("the schedule has 12 instalments, in order", s == 200 and len(rows) == 12
          and [r["number"] for r in rows] == list(range(1, 13)), (s, d))
    check("the first instalment is Rs 1,000 of interest on Rs 1,00,000", rows
          and rows[0]["interestPaise"] == 100_000 and rows[0]["principalPaise"] == 788_488, rows[:1])
    check("the principal repaid sums back to the principal",
          sum(r["principalPaise"] for r in rows) == 10_000_000, sum(r["principalPaise"] for r in rows))
    check("the interest charged sums to totalInterestPaise",
          sum(r["interestPaise"] for r in rows) == 661_853, sum(r["interestPaise"] for r in rows))
    check("the last instalment closes the loan to zero", rows and rows[-1]["outstandingAfterPaise"] == 0, rows[-1:])
    s, _, d = call("GET", GW + "/v1/liabilities/%s/schedule" % lid, headers=bearer(ravi))
    check("another account's schedule: 404", s == 404, (s, d))
    s, _, d = call("GET", GW + "/v1/liabilities/" + lid)
    check("a liability without a token: 401", s == 401, (s, d))

    # ---- scenarios: a copy of the account, not a view
    s, _, d = call("POST", GW + "/v1/scenarios", {"name": "Nope", "monthlyIncomePaise": 10_000_000,
                   "adjustments": [{"kind": "removeLiability", "liabilityId": str(uuid.uuid4())}]}, key(asha))
    check("an adjustment the snapshot cannot satisfy: 422", s == 422 and code(d) == "SCENARIO_ADJUSTMENT_INVALID",
          (s, d))
    s, _, d = call("POST", GW + "/v1/scenarios", {"name": "Nope", "monthlyIncomePaise": 10_000_000,
                   "adjustments": [{"kind": "winTheLottery"}]}, key(asha))
    check("an unknown adjustment kind: 422", s == 422 and code(d) == "SCENARIO_ADJUSTMENT_INVALID", (s, d))

    s, h, sc = call("POST", GW + "/v1/scenarios", {"name": "Can I take a car loan?",
                    "monthlyIncomePaise": 10_000_000, "adjustments": []}, key(asha))
    snap = (sc or {}).get("baseSnapshot") or {}
    ok = s == 201 and snap.get("monthlyIncomePaise") == 10_000_000
    check("a scenario copies the account in: 201", ok and any(l["id"] == lid for l in snap.get("liabilities") or [])
          and all(b["id"] != bid for b in snap.get("budgets") or []), (s, sc))
    if not ok:
        return
    sid = sc["id"]
    outgo = sum(b["limitPaise"] for b in snap["budgets"]) + sum(x["emiPaise"] for x in snap["liabilities"])
    s, _, d = call("POST", GW + "/v1/scenarios/%s:evaluate" % sid, None, bearer(asha))
    check("evaluating with no adjustments: no delta", s == 200 and d["monthlyOutgoBeforePaise"] == outgo
          and d["deltaPaise"] == 0 and d["surplusBeforePaise"] == 10_000_000 - outgo, (s, d))

    car = {"kind": "addLiability", "lender": "Car loan", "principalPaise": 50_000_000, "aprBps": 1000,
           "tenureMonths": 60}
    s, _, d = call("PATCH", GW + "/v1/scenarios/" + sid, {"adjustments": [car]},
                   bearer(asha, **{"If-Match": '"0"'}), content_type="application/merge-patch+json")
    check("adding a hypothetical loan: 200", s == 200 and len(d.get("adjustments") or []) == 1, (s, d))
    s, _, d = call("POST", GW + "/v1/scenarios/%s:evaluate" % sid, None, bearer(asha))
    check("Rs 5,00,000 at 10 % over 60 months costs Rs 10,623.52 a month, from the same engine",
          s == 200 and d["deltaPaise"] == 1_062_352 and d["monthlyOutgoAfterPaise"] == outgo + 1_062_352, (s, d))
    check("the hypothetical line is marked added", s == 200
          and [x["added"] for x in d["lines"]].count(True) == 1, (s, d))
    check("feasibility is income against the adjusted outgo", s == 200
          and d["feasible"] == (d["surplusAfterPaise"] >= 0)
          and d["surplusAfterPaise"] == d["monthlyIncomePaise"] - d["monthlyOutgoAfterPaise"], (s, d))
    s, _, d = call("PATCH", GW + "/v1/scenarios/" + sid,
                   {"adjustments": [car, {"kind": "setMonthlyIncome", "amountPaise": 1_000_000}]},
                   bearer(asha, **{"If-Match": '"1"'}), content_type="application/merge-patch+json")
    s, _, d = call("POST", GW + "/v1/scenarios/%s:evaluate" % sid, None, bearer(asha))
    check("an income of Rs 10,000 does not cover it: not feasible", s == 200 and d["feasible"] is False
          and d["monthlyIncomePaise"] == 1_000_000, (s, d))
    check("surplusBefore still uses the snapshot's own income", s == 200
          and d["surplusBeforePaise"] == 10_000_000 - outgo, (s, d))

    s, _, _ = call("DELETE", GW + "/v1/liabilities/" + lid, headers=bearer(asha))
    check("closing the liability: 204", s == 204, s)
    s, _, d = call("GET", GW + "/v1/liabilities/" + lid, headers=bearer(asha))
    check("it is closed, not gone", s == 200 and d["status"] == "closed", (s, d))
    s, _, d = call("POST", GW + "/v1/scenarios/%s:evaluate" % sid, None, bearer(asha))
    check("the copy does not follow the account: the answer is unchanged", s == 200
          and d["monthlyOutgoBeforePaise"] == outgo, (s, d))
    s, _, d = call("POST", GW + "/v1/scenarios/%s:evaluate" % sid, None, bearer(ravi))
    check("another account cannot evaluate it: 404", s == 404, (s, d))
    s, _, d = call("POST", GW + "/v1/scenarios/%s:explode" % sid, None, bearer(asha))
    check("an unknown action on a scenario: 404", s == 404, (s, d))
    s, _, d = call("GET", GW + "/v1/scenarios?limit=50", headers=bearer(ravi))
    check("ravi sees none of asha's scenarios", s == 200
          and all(x["id"] != sid for x in d.get("data") or []), (s, d))


if __name__ == "__main__":
    main()
    print(f"# P13 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
