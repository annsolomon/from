#!/usr/bin/env python3
"""P12 API smoke: statement upload -> real ai-parser -> review -> confirm, through the gateway (:8080), the object
store with S3 identities (:8333) and Kafka. Flow, as the demo user asha on a fresh smoke card (random last4):
create an upload (Idempotency-Key, replay) -> anonymous PUT refused (403) -> presigned PUT of a mock Saffron statement
(with a full test card number on its payment row) -> submit (202, replay) -> the parser's result arrives
(parsed, 17 lines, 1 redaction, no full PAN anywhere) -> review lines -> confirm without one line (16 card
transactions, source statement) -> confirm again 409 -> the same statement in the Indus and Deccan layouts imports
only the line left out (everything else is a duplicate) -> the same file again 409 -> a non-PDF 422 -> an unknown
layout fails as UNKNOWN_FORMAT -> isolation (ravi 404, anonymous 401); the smoke card is removed at the end.
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
    req = urllib.request.Request(url, data=data, method="PUT", headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:  # noqa: BLE001
        return 0


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
    unsigned = u["upload"]["url"].split("?")[0]
    check("object store refuses an unsigned PUT (403)", put(unsigned, pdf) == 403)
    s, _, d = call("POST", GW + "/v1/statements/uploads/%s:submit" % u["id"], None, key(asha))
    check("submit before the upload: 409 STATEMENT_NOT_UPLOADED", s == 409 and code(d) == "STATEMENT_NOT_UPLOADED",
          (s, d))
    st = put(u["upload"]["url"], pdf, u["upload"]["headers"])
    check("presigned PUT to the object store: 200", st == 200, st)

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


if __name__ == "__main__":
    main()
    print(f"# P12 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
