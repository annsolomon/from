#!/usr/bin/env python3
"""P11 API smoke: the portfolio through the gateway (:8080) as the demo users. Flow: add a card for a seeded catalog
product (Idempotency-Key, replay) -> unknown product 400 -> record a card transaction (statement + reward) -> a
ledger expense on the same card (ledger API) -> the auto-matcher suggests the pair via Kafka (ledger.expense ->
portfolio) -> confirm -> the transaction carries the expense -> statements -> EMI plan (₹1,00,000 at 15 % for 12
months: EMI ₹9,025.83) + schedule -> rewards balance/ledger -> monthly report; cross-tenant 404, anonymous 401;
the smoke card is removed at the end. Prints PASS/FAIL; exit 1 on failure.

Usage: p11_smoke.py http://localhost:8080
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
    sys.argv.append("http://127.0.0.1:8025")  # p04_smoke reads a Mailpit URL at import; P11 sends no mail
import p04_smoke as p4  # noqa: E402

GW = sys.argv[1].rstrip("/")
call, check, code, bearer = p4.call, p4.check, p4.code, p4.bearer
SLUG = "mock-saffron-millennia-cashback"


def login(email, pw):
    s, h, t = call("POST", GW + "/v1/auth/login", {"email": email, "password": pw},
                   {"X-Forwarded-For": "198.51.100.%d" % random.randint(1, 254)})
    return (t or {}).get("accessToken") if s == 200 else None


def key(tok):
    return bearer(tok, **{"Idempotency-Key": str(uuid.uuid4())})


def main():
    pa, pr = os.environ.get("DEMO_PASSWORD_ASHA"), os.environ.get("DEMO_PASSWORD_RAVI")
    if not check("DEMO_PASSWORD_ASHA / _RAVI set", bool(pa and pr)):
        return
    asha, ravi = login("asha@demo.in", pa), login("ravi@demo.in", pr)
    if not check("asha + ravi sign in through the gateway", bool(asha and ravi)):
        return

    s, _, d = call("GET", GW + "/v1/cards")
    check("cards without a token: 401 at the edge", s == 401, (s, d))
    s, _, d = call("GET", GW + "/v1/cards?limit=50", headers=bearer(asha))
    check("asha's seeded cards: 200, keyset page", s == 200 and isinstance(d.get("data"), list) and "page" in d, (s, d))

    s, _, product = call("GET", GW + "/v1/catalog/cards/" + SLUG)
    if not check("seeded catalog card is public", s == 200, (s, product)):
        return
    last4 = "%04d" % random.randint(0, 9999)
    body = {"cardProductId": product["id"], "last4": last4, "statementDay": 5, "dueOffsetDays": 20,
            "nickname": "Smoke " + last4}
    h1 = key(asha)
    s, h, card = call("POST", GW + "/v1/cards", body, h1)
    if not check("add card: 201, ETag \"0\", Location, current cycle",
                 s == 201 and h.get("etag") == '"0"' and card.get("currentCycle", {}).get("dueDate")
                 and card.get("productSlug") == SLUG, (s, h.get("etag"), card)):
        return
    s, h, again = call("POST", GW + "/v1/cards", body, h1)
    check("same key again: replayed, same card", s == 201 and h.get("idempotent-replayed") == "true"
          and again.get("id") == card["id"], (s, h, again))
    s, _, d = call("POST", GW + "/v1/cards", body, key(asha))
    check("same product + last4 again: 409 CARD_EXISTS", s == 409 and code(d) == "CARD_EXISTS", (s, d))
    s, _, d = call("POST", GW + "/v1/cards", dict(body, cardProductId=str(uuid.uuid4())), key(asha))
    errs = (d or {}).get("errors") or [{}]
    check("unknown product: 400 UNKNOWN_CARD_PRODUCT", s == 400 and errs[0].get("code") == "UNKNOWN_CARD_PRODUCT",
          (s, d))
    s, _, d = call("POST", GW + "/v1/cards", body, bearer(asha))
    check("no Idempotency-Key: 400", s == 400, (s, d))
    s, _, d = call("GET", GW + "/v1/cards/" + card["id"], headers=bearer(ravi))
    check("ravi reads asha's card: 404", s == 404, (s, d))

    # ---- a card transaction, then the same spend in the ledger -> auto-match via Kafka
    posted = (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(hours=2)).replace(microsecond=0)
    amount = random.randint(20_000, 90_000)
    s, h, txn = call("POST", GW + "/v1/cards/%s/transactions" % card["id"],
                     {"postedAt": posted.isoformat().replace("+00:00", "Z"), "amountPaise": amount, "kind": "purchase",
                      "description": "SWIGGY*BANGALORE " + last4, "categoryPath": "food.delivery"}, key(asha))
    if not check("record transaction: 201, statement + reward", s == 201 and txn.get("statementId")
                 and txn.get("reward") is not None, (s, txn)):
        return
    s, _, d = call("GET", GW + "/v1/cards/%s/transactions?limit=10" % card["id"], headers=bearer(asha))
    check("transactions list: the new one first", s == 200 and d["data"] and d["data"][0]["id"] == txn["id"], (s, d))
    s, _, exp = call("POST", GW + "/v1/expenses", {"occurredAt": posted.isoformat().replace("+00:00", "Z"),
                                                   "amountPaise": amount, "merchant": "Swiggy",
                                                   "categoryPath": "food.delivery", "paymentMethod": "card",
                                                   "userCardId": card["id"]}, key(asha))
    if not check("ledger expense on the same card: 201", s == 201, (s, exp)):
        return
    sug = None
    for _ in range(90):
        s, _, d = call("GET", GW + "/v1/links/suggestions?limit=100", headers=bearer(asha))
        if s == 200:
            sug = next((x for x in d["data"] if x["transactionId"] == txn["id"]), None)
        if sug:
            break
        time.sleep(1)
    check("auto-match suggestion arrives (ledger.expense.created -> portfolio)",
          sug is not None and sug["expenseId"] == exp["id"] and sug["score"] >= 0.9, sug)
    if sug:
        s, _, d = call("POST", GW + "/v1/links/%s:confirm" % sug["id"], None, bearer(asha))
        check("confirm: 200 confirmed", s == 200 and d.get("status") == "confirmed", (s, d))
        s, _, d = call("POST", GW + "/v1/links/%s:confirm" % sug["id"], None, bearer(ravi))
        check("ravi confirms asha's link: 404", s == 404, (s, d))
        s, _, d = call("GET", GW + "/v1/cards/%s/transactions/%s" % (card["id"], txn["id"]), headers=bearer(asha))
        check("the transaction carries the expense", s == 200 and d.get("expenseId") == exp["id"], (s, d))

    s, _, d = call("GET", GW + "/v1/cards/%s/statements" % card["id"], headers=bearer(asha))
    st = (d or {}).get("data") or [{}]
    check("statements: the open period holds the spend", s == 200 and st[0].get("status") == "open"
          and st[0].get("debitsPaise", 0) >= amount, (s, d))

    # ---- EMI
    s, h, plan = call("POST", GW + "/v1/emi-plans", {"userCardId": card["id"], "description": "Smoke laptop",
                                                     "principalPaise": 10_000_000, "annualRateBps": 1500,
                                                     "tenureMonths": 12}, key(asha))
    check("EMI plan: 201, EMI ₹9,025.83, interest ₹8,309.97", s == 201 and plan.get("emiPaise") == 902_583
          and plan.get("totalInterestPaise") == 830_997, (s, plan))
    if s == 201:
        s, _, d = call("GET", GW + "/v1/emi-plans/%s/schedule?limit=25" % plan["id"], headers=bearer(asha))
        rows = (d or {}).get("data") or []
        check("schedule: 12 installments, last leaves 0 outstanding", s == 200 and len(rows) == 12
              and rows[-1]["outstandingAfterPaise"] == 0, (s, len(rows)))
        s, _, d = call("DELETE", GW + "/v1/emi-plans/" + plan["id"], headers=bearer(asha))
        check("close the plan: 2xx", s in (200, 204), (s, d))

    # ---- rewards + report
    s, _, d = call("GET", GW + "/v1/rewards/balance", headers=bearer(asha))
    check("rewards balance: 200", s == 200 and "points" in d and isinstance(d.get("cards"), list), (s, d))
    s, _, d = call("GET", GW + "/v1/rewards/ledger?limit=10", headers=bearer(asha))
    check("rewards ledger: 200 keyset page", s == 200 and "page" in d, (s, d))
    month = posted.astimezone(datetime.timezone(datetime.timedelta(hours=5, minutes=30))).strftime("%Y-%m")
    s, _, d = call("GET", GW + "/v1/portfolio/report?period=month&month=" + month, headers=bearer(asha))
    mine = next((c for c in (d or {}).get("cards", []) if c["userCardId"] == card["id"]), None)
    check("monthly report: the smoke card's spend", s == 200 and mine and mine["spendPaise"] >= amount, (s, d))
    s, _, d = call("GET", GW + "/v1/portfolio/report?period=week", headers=bearer(asha))
    check("report period=week: 400", s == 400, (s, d))

    s, _, d = call("DELETE", GW + "/v1/cards/" + card["id"], headers=bearer(asha))
    check("remove the smoke card: 204", s == 204, (s, d))
    s, _, d = call("GET", GW + "/v1/cards/" + card["id"], headers=bearer(asha))
    check("removed card reads status removed", s == 200 and d.get("status") == "removed", (s, d))


if __name__ == "__main__":
    main()
    print(f"# P11 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
