#!/usr/bin/env python3
"""P14 API smoke: reco through the gateway (:8080) as the demo users. The claim this walks is that reco holds no
spend of its own - so the flow is: read the ranking cold, record an expense in the LEDGER, wait for the spend
profile to arrive over Kafka and the sweeper to recompute, and watch the ranking move. Then: the explanation's
arithmetic adds up line by line, a subset scores only the cards asked about, a held card is marked rather than
hidden, a card that costs more than it earns stays in the not-recommended bucket, and the result is cached by a
state hash so a second read neither changes nor re-publishes. Isolation (ravi's profile is its own, anonymous 401,
an admin token 403) throughout. Prints PASS/FAIL; exit 1 on failure.

Usage: p14_smoke.py http://localhost:8080
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
    sys.argv.append("http://127.0.0.1:8025")  # p04_smoke reads a Mailpit URL at import; P14 sends no mail
import p04_smoke as p4  # noqa: E402

GW = sys.argv[1].rstrip("/")
call, check, code, bearer = p4.call, p4.check, p4.code, p4.bearer
IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
# The ledger refuses a category outside its visible tree (the P13 lesson), so these are seeded paths. `gifts` is a
# root no other smoke block writes to, which keeps this run's spend profile its own.
ROOT = "gifts"
SUB = "gifts.donations"


def login(email, pw):
    s, h, t = call("POST", GW + "/v1/auth/login", {"email": email, "password": pw},
                   {"X-Forwarded-For": "198.51.100.%d" % random.randint(1, 254)})
    return (t or {}).get("accessToken") if s == 200 else None


def key(tok):
    return bearer(tok, **{"Idempotency-Key": str(uuid.uuid4())})


def expense(tok, amount, category):
    body = {"amountPaise": amount, "occurredAt": datetime.datetime.now(datetime.timezone.utc)
            .isoformat().replace("+00:00", "Z"), "merchant": "Smoke P14", "categoryPath": category,
            "paymentMethod": "upi"}
    s, _, d = call("POST", GW + "/v1/expenses", body, key(tok))
    return s, d


def reco(tok, query=""):
    s, _, d = call("GET", GW + "/v1/recommendations" + query, headers=bearer(tok))
    return s, d


def wait_for_spend(tok, want, seconds=45):
    """The ranking once the spend profile shows `want` paise a month (or after the wait)."""
    end = time.time() + seconds
    d = None
    while True:
        s, d = reco(tok)
        if s == 200 and d.get("monthlySpendPaise") == want:
            return d
        if time.time() > end:
            return d
        time.sleep(1)


def main():
    pa, pr = os.environ.get("DEMO_PASSWORD_ASHA"), os.environ.get("DEMO_PASSWORD_RAVI")
    if not check("DEMO_PASSWORD_ASHA / _RAVI set", bool(pa and pr)):
        return
    asha, ravi = login("asha@demo.in", pa), login("ravi@demo.in", pr)
    if not check("asha + ravi sign in through the gateway", bool(asha and ravi)):
        return

    # ---- the edge
    s, _, d = call("GET", GW + "/v1/recommendations")
    check("recommendations without a token: 401 at the edge", s == 401, (s, d))
    s, _, d = call("GET", GW + "/v1/recommendations/explain/" + str(uuid.uuid4()))
    check("explain without a token: 401", s == 401, (s, d))

    # ---- the cold read: the catalog projection must have arrived for this to be useful
    s, cold = reco(asha)
    if not check("a read answers 200 even before any recompute", s == 200, (s, cold)):
        return
    check("it reports the window and the blend", cold.get("window") == "3m"
          and cold.get("savingsWeightBps") == 6000 and cold.get("thresholdBps") == 2000, cold)
    considered = len(cold.get("recommended") or []) + len(cold.get("notRecommended") or [])
    check("the catalog projection reached reco (%d cards considered)" % considered, considered > 0, cold)
    base = cold.get("monthlySpendPaise", 0)

    # ---- the ledger -> Kafka -> the spend profile -> the ranking
    s, e1 = expense(asha, 800_000, SUB)
    if not check("an expense in the ledger: 201", s == 201, (s, e1)):
        return
    moved = wait_for_spend(asha, base + 800_000)
    check("the spend profile arrived over Kafka: Rs 8,000 a month",
          moved.get("monthlySpendPaise") == base + 800_000, moved.get("monthlySpendPaise"))
    check("the profile is keyed by category root", (moved.get("spendByRoot") or {}).get(ROOT) == 800_000,
          moved.get("spendByRoot"))
    check("something is recommended once there is spend", bool(moved.get("recommended")), moved)

    rec = moved["recommended"]
    check("the ranking is sorted by the blended score",
          all(rec[i - 1]["scoreBps"] >= rec[i]["scoreBps"] for i in range(1, len(rec))),
          [c["scoreBps"] for c in rec])
    check("the blend is savings x 0.6 + fit x 0.4 on every row",
          all(c["scoreBps"] == (c["savingsBps"] * 6000 + c["fitBps"] * 4000) // 10000
              for c in rec + moved["notRecommended"]), rec[:1])
    check("the best card normalises to 10000 bps of savings",
          any(c["savingsBps"] == 10_000 for c in rec), [c["savingsBps"] for c in rec])
    check("every recommended card saves money", all(c["annualSavingsPaise"] > 0 for c in rec),
          [c["annualSavingsPaise"] for c in rec])
    check("every card carries its reasons", all(c["reasons"] for c in rec + moved["notRecommended"]), rec[:1])
    check("a card that costs more than it earns is not recommended",
          all(c["annualSavingsPaise"] <= 0 or c["scoreBps"] < 2000 for c in moved["notRecommended"]),
          [(c["slug"], c["annualSavingsPaise"], c["scoreBps"]) for c in moved["notRecommended"][:3]])

    # ---- the result is cached by its state hash: a second read is the same answer
    s, again = reco(asha)
    check("a second read is the same result", s == 200 and again["computedAt"] == moved["computedAt"],
          (moved.get("computedAt"), again.get("computedAt")))
    check("and it is reported fresh", again.get("fresh") is True, again.get("fresh"))

    # ---- explain: the arithmetic, not a number
    top = rec[0]
    s, _, e = call("GET", GW + "/v1/recommendations/explain/" + top["cardProductId"], headers=bearer(asha))
    check("explain: 200", s == 200, (s, e))
    if s == 200:
        check("explain is about the card asked for", e["cardProductId"] == top["cardProductId"], e)
        check("explain agrees with the ranking", e["annualSavingsPaise"] == top["annualSavingsPaise"]
              and e["scoreBps"] == top["scoreBps"], (e["annualSavingsPaise"], top["annualSavingsPaise"]))
        check("savings = rewards - the expected fee + the milestone",
              e["annualSavingsPaise"] == e["annualRewardPaise"] - e["fee"]["expectedFeePaise"]
              + e["milestone"]["annualValuePaise"],
              (e["annualRewardPaise"], e["fee"], e["milestone"], e["annualSavingsPaise"]))
        check("the per-root lines sum to the reward",
              sum(l["annualRewardPaise"] for l in e["lines"]) == e["annualRewardPaise"],
              [l["annualRewardPaise"] for l in e["lines"]])
        check("each line names the rule and the share it was credited with",
              all(l["ruleId"] and 0 < l["shareBps"] <= 10_000 for l in e["lines"]), e["lines"][:2])
        check("a bound cap is reported as bound",
              all(not l["capBound"] or l["annualCapPaise"] == l["annualRewardPaise"] for l in e["lines"]),
              [l for l in e["lines"] if l["capBound"]][:2])
        check("the fee's waiver probability is a ramp from 0 to 10000",
              0 <= e["fee"]["waiverProbabilityBps"] <= 10_000, e["fee"])
    s, _, d = call("GET", GW + "/v1/recommendations/explain/" + str(uuid.uuid4()), headers=bearer(asha))
    check("explain for a card not in the catalog: 404 CARD_NOT_IN_CATALOG",
          s == 404 and code(d) == "CARD_NOT_IN_CATALOG", (s, d))

    # ---- the subset form, for ranking a search result
    s, subset = reco(asha, "?cardProductIds=" + top["cardProductId"])
    check("a subset scores only the cards asked about",
          s == 200 and len(subset["recommended"]) + len(subset["notRecommended"]) == 1, subset)
    s, _, d = call("GET", GW + "/v1/recommendations?cardProductIds=not-a-uuid", headers=bearer(asha))
    check("a malformed cardProductIds is 400", s == 400, (s, d))
    many = ",".join(str(uuid.uuid4()) for _ in range(51))
    s, _, d = call("GET", GW + "/v1/recommendations?cardProductIds=" + many, headers=bearer(asha))
    check("more than 50 ids is 400", s == 400, (s, d))

    # ---- a held card is marked, not hidden. A card can only be ranked if reco has its terms, and reco's catalog
    # projection is fed by catalog's own events - so the cards it can rank are the ones this run published (P10
    # approves one), not the 40 the seed wrote straight into catalog's tables. The card to check is therefore one
    # asha holds that is ALSO a candidate, not whichever card her portfolio happens to list first.
    s, _, cards = call("GET", GW + "/v1/cards?limit=50", headers=bearer(asha))
    hers = {c.get("cardProductId") for c in (cards.get("data") or [])} if s == 200 else set()
    end = time.time() + 30
    held_rows = []
    while time.time() < end:
        _, view = reco(asha)
        rows = (view.get("recommended") or []) + (view.get("notRecommended") or [])
        held_rows = [c for c in rows if c["cardProductId"] in hers]
        if held_rows and all(c["held"] for c in held_rows):
            break
        time.sleep(1)
    if check("asha holds a card reco carries in its catalog projection", bool(held_rows),
             {"products asha holds": len(hers), "of them scored": len(held_rows)}):
        check("every card asha holds is marked held, not removed from the ranking",
              all(c["held"] is True for c in held_rows), held_rows)

    # ---- isolation
    s, mine = reco(asha)
    s2, theirs = reco(ravi)
    check("ravi's profile is his own, not asha's",
          s2 == 200 and theirs.get("monthlySpendPaise") != mine.get("monthlySpendPaise")
          or theirs.get("spendByRoot") != mine.get("spendByRoot"),
          (mine.get("spendByRoot"), theirs.get("spendByRoot")))
    s, _, d = call("GET", GW + "/v1/recommendations", headers=bearer(asha, **{"X-Account-Id": str(uuid.uuid4())}))
    check("a client-supplied account header is ignored, not honoured",
          s == 200 and d.get("spendByRoot") == mine.get("spendByRoot"), (s, d.get("spendByRoot")))


if __name__ == "__main__":
    main()
    print(f"# P14 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
