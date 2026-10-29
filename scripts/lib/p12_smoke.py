#!/usr/bin/env python3
"""P12 API smoke: statement upload -> real ai-parser -> review -> confirm, through the gateway (:8080), the object
store with S3 identities (:8333) and Kafka. Flow, as the demo user asha on a fresh smoke card (random last4):
create an upload (Idempotency-Key, replay) -> anonymous PUT refused (403) -> presigned PUT of a mock Saffron statement
(with a full test card number on its payment row) -> submit (202, replay) -> the parser's result arrives
(parsed, 17 lines, 1 redaction, no full PAN anywhere) -> review lines -> confirm without one line (16 card
transactions, source statement) -> confirm again 409 -> the same statement in the Indus and Deccan layouts imports
only the line left out (everything else is a duplicate) -> the same file again 409 -> a non-PDF 422 -> an unknown
layout fails as UNKNOWN_FORMAT -> isolation (ravi 404, anonymous 401); the smoke card is removed at the end.

Then P12b (`calc`): every /v1/calc/* endpoint, asserted against the old repo's golden-fixture values in paise
(ADR-0018) - EMI, one cycle's ledger, minimum due, late fee + RBI stage, utilization, custom fees, the four
scenarios (incl. the 141-cycle minimum-only case), avalanche vs snowball, exit paths - plus POST-only, token-only,
the same answer twice and for another account (nothing is stored), and every cap refusing rather than computing.
Prints PASS/FAIL; exit 1 on failure.

Usage: p12_smoke.py http://localhost:8080
Env: DEMO_PASSWORD_ASHA, DEMO_PASSWORD_RAVI.
"""
import datetime
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
import uuid

HERE = os.path.dirname(__file__)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "..", "ai-parser"))
if len(sys.argv) < 3:
    sys.argv.append("http://127.0.0.1:8025")  # p04_smoke reads a Mailpit URL at import; P12 sends no mail
import p04_smoke as p4  # noqa: E402
from dexter_parser.pdftext import write_pdf  # noqa: E402  (the writer needs no pypdf)
from dexter_parser.samples import TEST_PAN, statement  # noqa: E402

GW = sys.argv[1].rstrip("/")
call, check, code, bearer = p4.call, p4.check, p4.code, p4.bearer
SLUG = "mock-saffron-millennia-cashback"
IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))


def login(email, pw):
    s, h, t = call("POST", GW + "/v1/auth/login", {"email": email, "password": pw},
                   {"X-Forwarded-For": "198.51.100.%d" % random.randint(1, 254)})
    return (t or {}).get("accessToken") if s == 200 else None


def key(tok):
    return bearer(tok, **{"Idempotency-Key": str(uuid.uuid4())})


def put(url, data, headers=None):
    """The PUT's HTTP status, or 'transport error ...' (retried once: a fresh connection after a reset)."""
    err = None
    for _ in range(2):
        req = urllib.request.Request(url, data=data, method="PUT", headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return r.status
        except urllib.error.HTTPError as e:
            return e.code
        except Exception as e:  # noqa: BLE001
            err = "transport error %s: %s" % (type(e).__name__, e)
            time.sleep(1)
    return err


def upload(tok, card_id, data, name="statement.pdf"):
    """create -> PUT -> submit; returns (upload id, submit status, submit body)."""
    s, _, u = call("POST", GW + "/v1/statements/uploads", {"userCardId": card_id, "fileName": name}, key(tok))
    if s != 201:
        return None, s, u
    put(u["upload"]["url"], data, u["upload"]["headers"])
    s, _, d = call("POST", GW + "/v1/statements/uploads/%s:submit" % u["id"], None, key(tok))
    return u["id"], s, d


def wait_decided(tok, upload_id, seconds=90):
    deadline = time.time() + seconds
    d = None
    while time.time() < deadline:
        s, _, d = call("GET", GW + "/v1/statements/uploads/" + upload_id, headers=bearer(tok))
        if s == 200 and d.get("status") in ("parsed", "failed"):
            return d
        time.sleep(1)
    return d


def lines(tok, upload_id):
    s, _, d = call("GET", GW + "/v1/statements/uploads/%s/lines?limit=100" % upload_id, headers=bearer(tok))
    return (d or {}).get("data") or [] if s == 200 else []


def confirm(tok, upload_id, exclude=None):
    body = {"excludeLines": exclude} if exclude else None
    return call("POST", GW + "/v1/statements/uploads/%s:confirm" % upload_id, body, key(tok))


def main():
    pa, pr = os.environ.get("DEMO_PASSWORD_ASHA"), os.environ.get("DEMO_PASSWORD_RAVI")
    if not check("DEMO_PASSWORD_ASHA / _RAVI set", bool(pa and pr)):
        return
    asha, ravi = login("asha@demo.in", pa), login("ravi@demo.in", pr)
    if not check("asha + ravi sign in through the gateway", bool(asha and ravi)):
        return
    s, _, d = call("GET", GW + "/v1/statements/uploads")
    check("uploads without a token: 401 at the edge", s == 401, (s, d))

    s, _, product = call("GET", GW + "/v1/catalog/cards/" + SLUG)
    if not check("seeded catalog card is public", s == 200, (s, product)):
        return
    last4 = "%04d" % random.randint(0, 9999)
    s, _, card = call("POST", GW + "/v1/cards", {"cardProductId": product["id"], "last4": last4, "statementDay": 5,
                                                  "nickname": "Statement smoke " + last4}, key(asha))
    if not check("smoke card: 201", s == 201, (s, card)):
        return
    try:
        run(asha, ravi, card, last4)
    finally:
        s, _, d = call("DELETE", GW + "/v1/cards/" + card["id"], headers=bearer(asha))
        check("remove the smoke card: 204", s == 204, (s, d))


def run(asha, ravi, card, last4):
    end = datetime.datetime.now(IST).date() - datetime.timedelta(days=1)
    seed = random.randint(1, 1_000_000)
    pdf, expected = statement("saffron", last4, end, 12, seed)

    # ---- create + presigned PUT (identities on: an unsigned PUT is refused)
    body = {"userCardId": card["id"], "fileName": "saffron-%s.pdf" % end, "sizeBytes": len(pdf)}
    h1 = key(asha)
    s, h, u = call("POST", GW + "/v1/statements/uploads", body, h1)
    if not check("create upload: 201, presigned PUT, awaiting_upload", s == 201 and u.get("status") == "awaiting_upload"
                 and u.get("upload", {}).get("method") == "PUT", (s, u)):
        return
    s2, h2, again = call("POST", GW + "/v1/statements/uploads", body, h1)
    check("same key again: replayed", s2 == 201 and h2.get("idempotent-replayed") == "true"
          and again.get("id") == u["id"], (s2, h2, again))
    s, _, d = call("POST", GW + "/v1/statements/uploads/%s:submit" % u["id"], None, key(asha))
    check("submit before the upload: 409 STATEMENT_NOT_UPLOADED", s == 409 and code(d) == "STATEMENT_NOT_UPLOADED",
          (s, d))
    st = put(u["upload"]["url"], pdf, u["upload"]["headers"])
    check("presigned PUT to the object store: 200", st == 200, (st, u["upload"]["url"].split("?")[0]))
    st = put(u["upload"]["url"].split("?")[0] + ".unsigned", b"%PDF-1.4 x", u["upload"]["headers"])
    check("object store refuses an unsigned PUT (403)", st == 403, st)

    hs = key(asha)
    s, h, d = call("POST", GW + "/v1/statements/uploads/%s:submit" % u["id"], None, hs)
    if not check("submit: 202 submitted (scan passed)", s == 202 and d.get("status") == "submitted"
                 and len(d.get("sha256") or "") == 64, (s, d)):
        return
    s, h, d = call("POST", GW + "/v1/statements/uploads/%s:submit" % u["id"], None, hs)
    check("submit again with the same key: replayed 202", s == 202 and h.get("idempotent-replayed") == "true", (s, h))

    # ---- the real ai-parser via Kafka
    d = wait_decided(asha, u["id"])
    p = (d or {}).get("parsed") or {}
    if not check("parsed by the ai-parser via Kafka (within 90 s)", (d or {}).get("status") == "parsed", d):
        return
    check("parsed header: Saffron, 17 lines, card matches, 1 redaction, due date",
          p.get("issuer") == "MOCK_SAFFRON" and p.get("lineCount") == 17 and p.get("cardMatches") is True
          and p.get("redactions") == 1 and p.get("dueDate") == expected["dueDate"]
          and p.get("totalDuePaise") == expected["totalDuePaise"], p)
    rows = lines(asha, u["id"])
    check("lines = the statement, in order", [(r["lineNo"], r["amountPaise"], r["kind"]) for r in rows]
          == [(x["lineNo"], x["amountPaise"], x["kind"]) for x in expected["lines"]], rows[:3])
    blob = json.dumps(d) + json.dumps(rows)
    check("no full card number anywhere (PAN redacted before parsing)",
          TEST_PAN not in blob and TEST_PAN.replace(" ", "") not in blob and "XXXX XXXX XXXX 1111" in blob)

    # ---- confirm all but the first purchase
    skip = next(r["lineNo"] for r in rows if r["kind"] == "purchase")
    s, _, c = confirm(asha, u["id"], [skip])
    res = (c or {}).get("result") or {}
    check("confirm: 16 imported, 1 excluded", s == 200 and res.get("imported") == 16 and res.get("excluded") == 1,
          (s, c))
    s, _, t = call("GET", GW + "/v1/cards/%s/transactions?limit=50" % card["id"], headers=bearer(asha))
    data = (t or {}).get("data") or []
    check("16 card transactions, source statement", s == 200 and len(data) == 16
          and all(x["source"] == "statement" for x in data), (s, len(data)))
    s, _, d = confirm(asha, u["id"])
    check("confirm again: 409 STATEMENT_WRONG_STATE", s == 409 and code(d) == "STATEMENT_WRONG_STATE", (s, d))

    # ---- the same statement in the other two layouts: only the excluded line is new
    for fmt, imported in (("indus", 1), ("deccan", 0)):
        other, _ = statement(fmt, last4, end, 12, seed)
        uid, s, d = upload(asha, card["id"], other, fmt + ".pdf")
        if not check(f"{fmt} layout submitted: 202", s == 202, (s, d)):
            continue
        d = wait_decided(asha, uid)
        dup = ((d or {}).get("parsed") or {}).get("duplicateLines")
        check(f"{fmt}: parsed, {17 - imported} duplicate lines flagged",
              (d or {}).get("status") == "parsed" and dup == 17 - imported, d)
        s, _, c = confirm(asha, uid)
        res = (c or {}).get("result") or {}
        check(f"{fmt}: confirm imports {imported}, skips {17 - imported} duplicates",
              s == 200 and res.get("imported") == imported and res.get("duplicates") == 17 - imported, (s, c))
    s, _, t = call("GET", GW + "/v1/cards/%s/transactions?limit=50" % card["id"], headers=bearer(asha))
    check("17 transactions in all (each statement line once)", s == 200 and len(t.get("data") or []) == 17,
          (s, len((t or {}).get("data") or [])))

    # ---- refused and failed files
    uid, s, d = upload(asha, card["id"], pdf)
    check("the same file again: 409 STATEMENT_ALREADY_UPLOADED", s == 409
          and code(d) == "STATEMENT_ALREADY_UPLOADED" and d.get("uploadId") == u["id"], (s, d))
    uid, s, d = upload(asha, card["id"], b"PK\x03\x04 not a statement", "zip.pdf")
    check("a non-PDF: 422 STATEMENT_FILE_REJECTED NOT_A_PDF", s == 422 and code(d) == "STATEMENT_FILE_REJECTED"
          and d.get("reason") == "NOT_A_PDF", (s, d))
    odd = write_pdf([[(40, "Some Unknown Bank statement %d" % seed)], [(40, "01/01/2026 X 1.00")]])
    uid, s, d = upload(asha, card["id"], odd, "odd.pdf")
    if check("unknown layout submitted: 202", s == 202, (s, d)):
        d = wait_decided(asha, uid)
        check("unknown layout: failed UNKNOWN_FORMAT", (d or {}).get("status") == "failed"
              and d.get("errorCode") == "UNKNOWN_FORMAT", d)

    # ---- isolation
    s, _, d = call("GET", GW + "/v1/statements/uploads/" + u["id"], headers=bearer(ravi))
    check("ravi reads asha's upload: 404", s == 404, (s, d))
    s, _, d = call("GET", GW + "/v1/statements/uploads?limit=5", headers=bearer(asha))
    check("asha's uploads: keyset page", s == 200 and "page" in d and d["data"], (s, d))

    calc(asha, ravi)


# --------------------------------------------------------------------------------------------- P12b: /v1/calc/*

TERMS_42 = {"aprBps": 4200, "cashAdvanceAprBps": 4200,
            "paymentAllocationOrder": ["retail_purchases", "cash_advances"]}
HIGHER_OF = {"formulaType": "HIGHER_OF", "percentBps": 500, "flatAmountPaise": 20000}
TIERS = [{"minOutstandingPaise": 0, "maxOutstandingPaise": 10000, "lateFeePaise": 0},
         {"minOutstandingPaise": 100100, "maxOutstandingPaise": 500000, "lateFeePaise": 60000}]
FEES = [{"id": "f1", "name": "Annual", "appliesTo": "ONCE", "amountType": "FLAT", "flatPaise": 50000},
        {"id": "f2", "name": "Service", "appliesTo": "EVERY_CYCLE", "amountType": "PERCENT", "percentBps": 200}]


def calc(asha, ravi):
    """The ported engines behind /v1/calc/* (ADR-0018). Expected values are the old repo's golden fixtures in paise,
    the same ones libs/portfolio-calc asserts as a unit: here they must survive the gateway and the JSON contract.
    Note every call is a plain bearer POST - no Idempotency-Key, because calc stores nothing."""
    def post(path, body, tok=asha):
        return call("POST", GW + "/v1/calc" + path, body, bearer(tok) if tok else None)

    s, _, d = post("/emi", {"principalPaise": 100000, "annualRateBps": 1500, "tenureMonths": 12}, None)
    check("calc without a token: 401 at the edge", s == 401, (s, d))
    s, _, d = call("GET", GW + "/v1/calc/emi", headers=bearer(asha))
    check("calc is POST only: GET is 405", s == 405 and code(d) == "METHOD_NOT_ALLOWED", (s, d))

    s, _, d = post("/emi", {"principalPaise": 10000000, "annualRateBps": 1500, "tenureMonths": 12,
                            "processingFeePaise": 19900, "firstDueDate": "2026-11-05"})
    check("emi: Rs 9,025.83 x 12 on Rs 1,00,000 at 15 %", s == 200 and d.get("emiPaise") == 902583
          and d.get("totalInterestPaise") == 830997 and len(d.get("installments") or []) == 12
          and d["installments"][11]["outstandingAfterPaise"] == 0, (s, d))

    ledger_body = {"terms": TERMS_42, "cycleStart": "2026-01-01", "cycleEnd": "2026-01-30", "transactions": [
        {"date": "2026-01-01", "type": "PURCHASE", "amountPaise": 500000, "bucket": "retail_purchases",
         "referenceNumber": "TXN-1"},
        {"date": "2026-01-10", "type": "PAYMENT", "amountPaise": 200000}]}
    s, _, d = post("/ledger", ledger_body)
    check("ledger: Rs 124.27 interest, Rs 3,000 carried", s == 200 and d.get("totalInterestPaise") == 12427
          and len(d.get("closingLots") or []) == 1 and d["closingLots"][0]["remainingPaise"] == 300000, (s, d))

    s, _, d = post("/minimum-due", {
        "terms": {"formulaType": "FINANCE_CHARGE_CONDITIONAL", "flatFloorPaise": 20000,
                  "retailCashPercentBps": 500, "roundUpToNearestPaise": 1000},
        "retailCashOutstandingPaise": 800000, "financeChargesPaise": 15000, "gstAmountPaise": 2700,
        "totalAmountDuePaise": 817700})
    check("minimum due: blended 5 % beats the finance charge -> Rs 437", s == 200
          and d.get("minimumDuePaise") == 43700, (s, d))

    s, _, d = post("/late-fee", {"outstandingBalancePaise": 320000, "daysOverdue": 15, "tiers": TIERS})
    check("late fee: Rs 600 + GST, SMA-0 and reported", s == 200 and d.get("lateFeePaise") == 60000
          and d.get("gstOnLateFeePaise") == 10800 and d["delinquency"]["stage"] == "SMA_0"
          and d["delinquency"]["reportedToBureaus"], (s, d))

    s, _, d = post("/utilization", {"spentPaise": 12000000, "creditLimitPaise": 10000000,
                                    "overlimitFee": {"percentBps": 250, "flatMinimumPaise": 55000}})
    check("utilization: 120 %, over by Rs 20,000, flat Rs 550 fee wins", s == 200
          and d["utilization"]["utilizationBps"] == 12000 and d["utilization"]["overLimitPaise"] == 2000000
          and d["utilization"]["availableCreditPaise"] == 0 and d.get("overlimitFeePaise") == 55000, (s, d))

    s, _, d = post("/custom-fees", {"fees": FEES, "balancePaise": 1000000, "cycleNumber": 1})
    check("custom fees cycle 1: Rs 700 + Rs 126 GST, 2 lines", s == 200 and d.get("totalPaise") == 70000
          and d.get("gstPaise") == 12600 and len(d.get("breakdown") or []) == 2, (s, d))
    s, _, d = post("/custom-fees", {"fees": FEES, "balancePaise": 1000000, "cycleNumber": 2})
    check("custom fees cycle 2: the one-off is skipped", s == 200 and d.get("totalPaise") == 20000
          and len(d.get("breakdown") or []) == 1, (s, d))

    s, _, d = post("/scenarios/full-payment", {"statementBalancePaise": 500000, "customFees": [FEES[0]]})
    check("full payment: Rs 5,590 all in, grace kept", s == 200 and d.get("totalCostPaise") == 559000
          and d.get("gracePreservedNextCycle") is True, (s, d))

    s, _, d = post("/scenarios/partial-payment", {
        "terms": {"aprBps": 4200, "cashAdvanceAprBps": 4200, "paymentAllocationOrder": ["retail_purchases"]},
        "transactions": [{"date": "2026-01-01", "type": "PURCHASE", "amountPaise": 500000}],
        "cycleStart": "2026-01-01", "cycleEnd": "2026-01-30", "paymentPaise": 200000})
    check("partial payment: statement Rs 5,172.60, Rs 3,000 carried, grace lost", s == 200
          and d.get("scenario") == "partial_payment" and d.get("statementBalancePaise") == 517260
          and d.get("carriedPrincipalIntoNextCyclePaise") == 300000
          and d.get("gracePreservedNextCycle") is False, (s, d))

    s, _, d = post("/scenarios/no-payment", {
        "terms": {"aprBps": 4200, "cashAdvanceAprBps": 4200, "paymentAllocationOrder": ["retail_purchases"]},
        "statementBalancePaise": 500000, "cycleLengthDays": 30, "lateFeeTiers": TIERS})
    p = (d or {}).get("projections") or []
    check("no payment: Rs 172.60 by day 30, Rs 6,319.02 and SMA-2 by day 90", s == 200 and len(p) == 3
          and p[0]["interestAccruedPaise"] == 17260 and p[0]["totalOutstandingPaise"] == 591167
          and p[2]["totalOutstandingPaise"] == 631902 and p[2]["delinquencyStage"]["stage"] == "SMA_2", (s, d))

    minimum_body = {"terms": {"aprBps": 4200, "cashAdvanceAprBps": 4200, "paymentAllocationOrder":
                              ["retail_purchases", "finance_charges", "fees"]},
                    "minimumDueTerms": HIGHER_OF, "startingPrincipalPaise": 1000000, "cycleLengthDays": 30}
    s, _, d = post("/scenarios/minimum-payment", minimum_body)
    check("minimum only: the 141-cycle case, Rs 30,820.88 extra", s == 200 and d.get("cyclesToPayoff") == 141
          and d.get("totalInterestPaidPaise") == 2611942 and d.get("totalGstPaidPaise") == 470146
          and d.get("totalExtraPaidPaise") == 3082088 and len(d.get("amortizationTable") or []) == 141
          and d.get("infiniteDebtWarning") is False, (s, d))
    s, _, again = post("/scenarios/minimum-payment", minimum_body)
    check("the same request twice: the same answer (stateless, no key needed)", s == 200 and again == d, s)
    s, _, other = post("/scenarios/minimum-payment", minimum_body, ravi)
    check("ravi gets the same answer (no tenant data in calc)", s == 200 and other == d, s)

    s, _, d = post("/scenarios/minimum-payment", {
        "terms": {"aprBps": 4200, "cashAdvanceAprBps": 4200,
                  "paymentAllocationOrder": ["retail_purchases", "finance_charges"]},
        "minimumDueTerms": {"formulaType": "FLAT", "flatAmountPaise": 10000},
        "startingPrincipalPaise": 5000000, "cycleLengthDays": 30})
    check("a minimum below the interest: never clears, caught on cycle 1", s == 200
          and d.get("infiniteDebtWarning") is True and d.get("cyclesToPayoff") is None
          and len(d.get("amortizationTable") or []) == 1, (s, d))

    cards = [{"id": "a", "name": "A", "balancePaise": 1000000, "aprBps": 4200, "minimumDuePaise": 50000},
             {"id": "b", "name": "B", "balancePaise": 500000, "aprBps": 1800, "minimumDuePaise": 25000}]
    s, _, d = post("/repayment-path", {"cards": cards, "monthlyBudgetPaise": 300000})
    check("repayment path: avalanche saves Rs 444.50 over snowball", s == 200
          and d.get("recommendedStrategy") == "avalanche"
          and d["avalanche"]["totalInterestPaidPaise"] == 149911
          and d["snowball"]["totalInterestPaidPaise"] == 194361
          and d.get("interestSavingsWithBestStrategyPaise") == 44450, (s, d))
    s, _, d = post("/repayment-path", {"cards": cards, "monthlyBudgetPaise": 70000})
    check("repayment path: a budget under the minimums is infeasible, not simulated", s == 200
          and d.get("feasible") is False and "less than total minimum due" in (d.get("message") or ""), (s, d))

    exit_body = {"terms": {"aprBps": 3600, "cashAdvanceAprBps": 3600,
                           "paymentAllocationOrder": ["finance_charges", "retail_purchases"]},
                 "minimumDueTerms": HIGHER_OF, "currentBalancePaise": 600000, "cycleLengthDays": 30,
                 "targetCycles": [3, 6, 12]}
    s, _, d = post("/exit-paths", exit_body)
    paths = (d or {}).get("targetedPaths") or []
    check("exit paths: Rs 2,141.01 a cycle clears Rs 6,000 in 3, Rs 423.80 of interest", s == 200
          and len(paths) == 3 and paths[0]["requiredPaymentPerCyclePaise"] == 214101
          and paths[0]["cyclesToDebtFree"] == 3 and paths[0]["totalInterestAndGstPaise"] == 42380
          and d["continueMinimum"]["totalExtraPaidPaise"] == 761255
          and d.get("recommendedPath") == "targeted_payoff_3cycles", (s, d))
    s, _, d = post("/exit-paths", dict(exit_body, currentBalancePaise=0))
    check("a zero balance has nothing to exit from", s == 200 and "already zero" in (d.get("message") or "")
          and not d.get("targetedPaths"), (s, d))

    # ---- the caps: refused, not computed
    s, _, d = post("/exit-paths", dict(exit_body, targetCycles=[1, 2, 3, 4, 5, 6, 12, 24]))
    check("8 target cycles: 400 VALIDATION_FAILED", s == 400 and code(d) == "VALIDATION_FAILED"
          and any(e.get("field") == "targetCycles" for e in (d.get("errors") or [])), (s, d))
    s, _, d = post("/ledger", dict(ledger_body, terms=dict(TERMS_42, aprBps=20000)))
    check("200 % APR: 400", s == 400 and code(d) == "VALIDATION_FAILED", (s, d))
    s, _, d = post("/ledger", dict(ledger_body, cycleEnd="2027-06-01"))
    check("an 18-month cycle: 400", s == 400 and code(d) == "VALIDATION_FAILED", (s, d))
    s, _, d = post("/ledger", dict(ledger_body, transactions=[
        {"date": "2026-01-01", "type": "PURCHASE", "amountPaise": 100} for _ in range(501)]))
    check("501 transactions: 400", s == 400 and code(d) == "VALIDATION_FAILED", (s, d))
    big = [{"date": "2026-01-01", "type": "PURCHASE", "amountPaise": 100, "bucket": "retail_purchases",
            "referenceNumber": "TXN-" + "0" * 50 + str(i)} for i in range(700)]
    s, _, d = post("/ledger", dict(ledger_body, transactions=big))
    check("a body over 64 KB: 413 PAYLOAD_TOO_LARGE", s == 413 and code(d) == "PAYLOAD_TOO_LARGE", (s, d))


if __name__ == "__main__":
    main()
    print(f"# P12 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
