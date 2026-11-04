#!/usr/bin/env python3
"""P15 API smoke: the wallet optimizer, best-card-now and the SSE stream through the gateway (:8080).

The figures a seeded run produces depend on what that run published, so almost nothing here is a hardcoded number.
What is checked instead are the identities the engine must satisfy whatever the catalog holds: a wallet's value is
its cards' values, a rupee is routed once, each size's marginal value is the step up from the size below, a reward
never exceeds the cap headroom reported beside it, and best-card-now's total is its own parts. Then the parts that
ARE exact: an expense on a card moves that card's headroom (A-86), a one-time ticket opens a stream and cannot be
used twice, and a recompute arrives as a push.

Usage: p15_smoke.py http://localhost:8080
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

sys.path.insert(0, os.path.dirname(__file__))
if len(sys.argv) < 3:
    sys.argv.append("http://127.0.0.1:8025")  # p04_smoke reads a Mailpit URL at import; P15 sends no mail
import p04_smoke as p4  # noqa: E402

GW = sys.argv[1].rstrip("/")
call, check, code, bearer = p4.call, p4.check, p4.code, p4.bearer
# the same seeded root P14's smoke uses, so this run's spend profile stays its own
ROOT = "gifts"
SUB = "gifts.donations"


def login(email, pw):
    s, h, t = call("POST", GW + "/v1/auth/login", {"email": email, "password": pw},
                   {"X-Forwarded-For": "198.51.100.%d" % random.randint(1, 254)})
    return (t or {}).get("accessToken") if s == 200 else None


def key(tok):
    return bearer(tok, **{"Idempotency-Key": str(uuid.uuid4())})


def wallet(tok, query=""):
    s, _, d = call("GET", GW + "/v1/recommendations/wallet" + query, headers=bearer(tok))
    return s, d


def best(tok, amount, category=None, merchant=None):
    q = "?amountPaise=%d" % amount
    if category:
        q += "&category=" + category
    if merchant:
        q += "&merchantId=" + merchant
    s, _, d = call("GET", GW + "/v1/recommendations/best-card" + q, headers=bearer(tok))
    return s, d


def expense_on_card(tok, amount, category, user_card_id):
    """An expense the ledger attributes to a card, which is what moves best-card-now's headroom."""
    body = {"amountPaise": amount, "occurredAt": datetime.datetime.now(datetime.timezone.utc)
            .isoformat().replace("+00:00", "Z"), "merchant": "Smoke P15", "categoryPath": category,
            "paymentMethod": "card", "userCardId": user_card_id}
    s, _, d = call("POST", GW + "/v1/expenses", body, key(tok))
    return s, d


def read_stream(ticket, seconds=8):
    """Reads the opening SSE event. The ticket is the credential, so no Authorization header is sent."""
    url = GW + "/v1/recommendations/stream?ticket=" + ticket
    req = urllib.request.Request(url, headers={"Accept": "text/event-stream"})
    got = b""
    try:
        with urllib.request.urlopen(req, timeout=seconds) as r:
            if r.status != 200:
                return r.status, ""
            end = time.time() + seconds
            while time.time() < end and b"\n\n" not in got:
                chunk = r.read(1)
                if not chunk:
                    break
                got += chunk
            return 200, got.decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:                                        # noqa: BLE001 - a timeout is an answer here
        return 0, "%s: %s" % (type(e).__name__, e)


def main():
    pa, pr = os.environ.get("DEMO_PASSWORD_ASHA"), os.environ.get("DEMO_PASSWORD_RAVI")
    if not check("DEMO_PASSWORD_ASHA / _RAVI set", bool(pa and pr)):
        return
    asha, ravi = login("asha@demo.in", pa), login("ravi@demo.in", pr)
    if not check("asha + ravi sign in through the gateway", bool(asha and ravi)):
        return

    # ---- the edge
    s, _, d = call("GET", GW + "/v1/recommendations/wallet")
    check("the wallet without a token: 401 at the edge", s == 401, (s, d))
    s, _, d = call("GET", GW + "/v1/recommendations/best-card?amountPaise=1000&category=" + SUB)
    check("best-card without a token: 401 at the edge", s == 401, (s, d))

    # ---- some spend, so there is something to optimize
    s, _, _ = call("POST", GW + "/v1/expenses", {"amountPaise": 400_000,
                   "occurredAt": datetime.datetime.now(datetime.timezone.utc).isoformat()
                   .replace("+00:00", "Z"), "merchant": "Smoke P15", "categoryPath": SUB,
                   "paymentMethod": "upi"}, key(asha))
    check("an expense is recorded in the ledger", s == 201, s)

    # ---- the wallet: identities that must hold whatever the catalog holds
    deadline = time.time() + 45
    sizes = []
    while time.time() < deadline:
        s, w = wallet(asha)
        sizes = (w or {}).get("bySize") or []
        if s == 200 and sizes:
            break
        time.sleep(1)
    if not check("the wallet answers once there is spend", bool(sizes), w):
        return
    check("it reports the window and the spend it used",
          w["window"] == "3m" and w["monthlySpendPaise"] > 0, (w.get("window"), w.get("monthlySpendPaise")))
    check("a wallet's value is the sum of its cards' values",
          all(x["annualValuePaise"] == sum(c["annualValuePaise"] for c in x["cards"]) for x in sizes),
          [(x["size"], x["annualValuePaise"]) for x in sizes])
    check("and its fees are the sum of its cards' fees",
          all(x["annualFeePaise"] == sum(c["expectedFeePaise"] for c in x["cards"]) for x in sizes), sizes[0])
    routed = []
    for x in sizes:
        seen = [r for c in x["cards"] for r in c["categoryRoots"]]
        routed.append(len(seen) == len(set(seen)))
    check("a rupee is spent once: no root is routed to two cards", all(routed), routed)
    check("the first size has no marginal value", sizes[0]["marginalValuePaise"] == 0, sizes[0])
    check("each later size's marginal value is the step up from the one below",
          all(sizes[i]["marginalValuePaise"]
              == sizes[i]["annualValuePaise"] - sizes[i - 1]["annualValuePaise"]
              for i in range(1, len(sizes))), [x["marginalValuePaise"] for x in sizes])
    check("every routed root belongs to a card that is in the wallet",
          all(set(x["routing"].values()) <= {c["cardProductId"] for c in x["cards"]} for x in sizes),
          sizes[-1]["routing"])
    check("the best size is the one worth the most",
          w["bestSize"] == max(sizes, key=lambda x: x["annualValuePaise"])["size"],
          (w["bestSize"], [(x["size"], x["annualValuePaise"]) for x in sizes]))
    check("the beam is reported and is no larger than the candidate set",
          0 < w["inBeam"] <= max(w["candidatesConsidered"], 1), (w["inBeam"], w["candidatesConsidered"]))
    check("every card in a wallet carries its reasons",
          all(c["reasons"] for x in sizes for c in x["cards"]), sizes[0]["cards"][0])

    s, one = wallet(asha, "?size=1")
    check("size=1 answers with one size only", s == 200 and len(one["bySize"]) == 1, s)
    s, _, d = call("GET", GW + "/v1/recommendations/wallet?size=0", headers=bearer(asha))
    check("size=0 is 400", s == 400, (s, d))
    s, _, d = call("GET", GW + "/v1/recommendations/wallet?size=9", headers=bearer(asha))
    check("size past three is 400", s == 400, (s, d))

    # ---- best-card-now
    s, b = best(asha, 250_000, category=SUB)
    if check("best-card: 200", s == 200, (s, b)):
        cards = b["cards"]
        check("it answers for the cards asha holds", isinstance(cards, list), type(cards).__name__)
        check("total = reward + offer + milestone on every card",
              all(c["totalPaise"] == c["rewardPaise"] + c["offerPaise"] + c["milestonePaise"] for c in cards),
              cards[:1])
        check("the ranking is sorted by total",
              all(cards[i - 1]["totalPaise"] >= cards[i]["totalPaise"] for i in range(1, len(cards))),
              [c["totalPaise"] for c in cards])
        check("the best card is the first row",
              not cards or b["bestUserCardId"] == cards[0]["userCardId"], b.get("bestUserCardId"))
        check("a reward never exceeds the headroom reported beside it",
              all(c["headroomPaise"] is None or c["rewardPaise"] <= c["headroomPaise"] for c in cards),
              [(c["rewardPaise"], c["headroomPaise"]) for c in cards])
        check("the period is this month", b["period"].endswith("-01"), b.get("period"))
        check("an exhausted cap says so rather than reporting a silent zero",
              all(not c["capExhausted"] or c["rewardPaise"] == 0 for c in cards), cards[:1])

    s, d = best(asha, 0, category=SUB)
    check("a zero amount is 400 AMOUNT_REQUIRED", s == 400 and code(d) == "AMOUNT_REQUIRED", (s, d))
    s, _, d = call("GET", GW + "/v1/recommendations/best-card?amountPaise=1000", headers=bearer(asha))
    check("neither merchant nor category is 400 MERCHANT_OR_CATEGORY_REQUIRED",
          s == 400 and code(d) == "MERCHANT_OR_CATEGORY_REQUIRED", (s, d))

    # ---- an expense on a held card moves that card's headroom (A-86)
    s, _, held = call("GET", GW + "/v1/cards?limit=50", headers=bearer(asha))
    mine = [c for c in (held.get("data") or [])] if s == 200 else []
    scored = []
    if mine:
        s, b = best(asha, 100_000, category=SUB)
        if s == 200:
            ids = {c["userCardId"] for c in b["cards"]}
            scored = [c for c in mine if c["id"] in ids]
    if check("asha holds a card reco can score", bool(scored), {"holds": len(mine), "scored": len(scored)}):
        target = scored[0]["id"]
        s, b = best(asha, 100_000, category=SUB)
        before = next(c for c in b["cards"] if c["userCardId"] == target)["periodSpendPaise"]
        s, _ = expense_on_card(asha, 300_000, SUB, target)
        check("an expense attributed to that card is recorded", s == 201, s)
        end = time.time() + 45
        after = before
        while time.time() < end:
            s, b = best(asha, 100_000, category=SUB)
            if s == 200:
                after = next((c for c in b["cards"] if c["userCardId"] == target), {}).get("periodSpendPaise",
                                                                                          before)
                if after > before:
                    break
            time.sleep(1)
        check("and the card's period spend moved by it, over Kafka (A-86)",
              after == before + 300_000, (before, after))

    # ---- the stream
    s, _, t = call("POST", GW + "/v1/recommendations/stream-ticket", None, key(asha))
    if check("a stream ticket is minted: 200", s == 200 and (t or {}).get("ticket"), (s, t)):
        check("it lasts 60 seconds", t["expiresInSeconds"] == 60, t)
        status, body = read_stream(t["ticket"])
        check("the stream opens with no token at all and sends an opening event",
              status == 200 and "event:recommendation." in body.replace("event: ", "event:"), (status, body[:200]))
        check("the opening event carries the state hash as its id",
              "id:" in body.replace("id: ", "id:") or "recommendation.none" in body, body[:200])
        status2, body2 = read_stream(t["ticket"], seconds=5)
        check("and the ticket cannot be used twice", status2 == 401, (status2, body2[:120]))
    s, _, d = call("GET", GW + "/v1/recommendations/stream?ticket=never-issued")
    check("an unissued ticket is 401 STREAM_TICKET_INVALID",
          s == 401 and code(d) == "STREAM_TICKET_INVALID", (s, d))

    # ---- isolation
    s, mineW = wallet(asha)
    s2, theirs = wallet(ravi)
    check("ravi's wallet is his own, not asha's",
          s2 == 200 and (theirs.get("monthlySpendPaise") != mineW.get("monthlySpendPaise")
                         or theirs.get("bySize") != mineW.get("bySize")),
          (mineW.get("monthlySpendPaise"), theirs.get("monthlySpendPaise")))
    s, _, d = call("GET", GW + "/v1/recommendations/wallet", headers=bearer(asha,
                   **{"X-Account-Id": str(uuid.uuid4())}))
    check("a client-supplied account header is ignored, not honoured",
          s == 200 and json.dumps(d.get("bySize")) == json.dumps(mineW.get("bySize")), s)


if __name__ == "__main__":
    main()
    print(f"# P15 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
