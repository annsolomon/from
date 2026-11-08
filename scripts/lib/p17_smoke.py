#!/usr/bin/env python3
"""P17 API smoke: commerce through the gateway (:8080).

The P15 lesson is the design rule: an identity asserted over an empty collection is not a test. Nothing here is read
from seeded state. Every account is REGISTERED by this script, every deal, campaign, click, conversion and
application is CREATED by this script, and every collection is asserted NOT EMPTY before anything is said about its
contents. The parts that travel are waited for and then asserted, never assumed: the held card (portfolio ->
Kafka -> commerce's held-cards projection) before card-match, the catalog card (catalog -> commerce's catalog
projection) before the application, billing's entitlements (a real PREMIUM checkout -> Kafka -> commerce's ad-free
projection) before AD_FREE, and the counter flusher (Redis -> Postgres every 10 s) before the stats.

  admin creates deals in a fresh category -> the public feed ranks them (NOT EMPTY) -> the golden saving ->
  a user holds the card -> card-match moves its deal first -> the signed redirect (302, subid) -> an altered
  token 400 -> a signed postback created / replayed / approved -> stats show the click, the conversion and the
  commission -> a card application (201, duplicate 409) decided by an operator (in_review -> approved) ->
  a campaign with a frequency cap -> capped serves -> PREMIUM is ad-free -> the ad click -> isolation.

Usage: p17_smoke.py http://localhost:8080 http://127.0.0.1:8025
       p17_smoke.py --setup <gateway> <mailpit>   (prints a deal, a click path and a fresh account as JSON;
                                                   trail-check uses it)
Env: COMMERCE_AFFILIATE_SECRET_MOCK (signs postbacks), DEMO_PASSWORD_ADMIN + .run/admin-totp (the operator).
"""
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(__file__))
SETUP_ONLY = len(sys.argv) > 1 and sys.argv[1] == "--setup"
if SETUP_ONLY:
    sys.argv.pop(1)
if len(sys.argv) < 3:
    sys.argv.append("http://127.0.0.1:8025")
import p04_smoke as p4  # noqa: E402
import p10_smoke as p10  # noqa: E402  (admin_token: password + TOTP from the P05 smoke's saved secret)
import p16_smoke as p16  # noqa: E402  (fresh_account: register + Mailpit verify + login)

GW = sys.argv[1].rstrip("/")
call, check, code, bearer = p4.call, p4.check, p4.code, p4.bearer
SLUG = "mock-saffron-millennia-cashback"   # promoted through catalog events by the P10 smoke


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code_, msg, headers, newurl):  # noqa: D401
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def raw_get(path, headers=None):
    """GET without following redirects: (status, headers, body)."""
    req = urllib.request.Request(GW + path, method="GET", headers=headers or {})
    try:
        with OPENER.open(req, timeout=15) as r:
            return r.status, {k.lower(): v for k, v in r.headers.items()}, r.read()
    except urllib.error.HTTPError as e:
        return e.code, {k.lower(): v for k, v in e.headers.items()}, e.read()


def sign(body, at=None):
    secret = os.environ.get("COMMERCE_AFFILIATE_SECRET_MOCK", "").encode()
    t = int(at if at is not None else time.time())
    return "t=%d,v1=%s" % (t, hmac.new(secret, f"{t}.".encode() + body, hashlib.sha256).hexdigest())


def postback(doc, signature=None, network="mock"):
    body = json.dumps(doc).encode()
    headers = {"Content-Type": "application/json"}
    if signature is not False:
        headers["Postback-Signature"] = signature or sign(body)
    req = urllib.request.Request(GW + "/v1/webhooks/affiliate/" + network, data=body, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"null")
        except ValueError:
            return e.code, raw.decode(errors="replace")


def wait(predicate, seconds=45, step=1.0):
    end = time.time() + seconds
    while True:
        v = predicate()
        if v:
            return v
        if time.time() > end:
            return None
        time.sleep(step)


def deal_body(title, category, eligible, reference, sponsored=False, weight=0):
    return {"merchantId": str(uuid.uuid4()), "merchantName": "Smoke Mart", "title": title,
            "categoryPath": category, "components": [{"kind": "percent", "valueBps": 1000, "capPaise": 500000},
                                                     {"kind": "percent", "valueBps": 500, "capPaise": 200000}],
            "eligibleCardProducts": eligible, "referenceSpendPaise": reference, "sponsored": sponsored,
            "sponsorWeight": weight, "network": "mock", "landingUrl": "https://merchant.example/smoke?ref=dexter",
            "commissionBps": 400}


def create_deal(admin, body):
    s, _, d = call("POST", GW + "/v1/admin/commerce/deals", body, bearer(admin))
    if s != 201:
        raise SystemExit(f"create deal: {s} {d}")
    return d


def feed(category, tok=None):
    s, _, d = call("GET", GW + "/v1/deals?limit=50&category=" + category, headers=bearer(tok) if tok else None)
    return s, (d or {}).get("data") or [] if isinstance(d, dict) else []


def stats_row(admin, deal_id):
    s, _, d = call("GET", GW + "/v1/admin/commerce/stats", headers=bearer(admin))
    if s != 200:
        return None
    return next((r for r in d.get("deals") or [] if r["dealId"] == deal_id), None)


def setup():
    """For trail-check: one admin-created deal in a fresh category, its click path and a fresh account."""
    admin = p10.admin_token()
    if not admin:
        raise SystemExit("no admin token")
    category = "trail" + uuid.uuid4().hex[:10] + ".online"
    d = create_deal(admin, deal_body("Trail deal", category, [], 7500000))
    acct = p16.fresh_account("trailp17")
    s, _, detail = call("GET", GW + "/v1/deals/" + d["id"], headers=bearer(acct["access"]))
    return {"category": category, "dealId": d["id"], "clickPath": detail["deal"]["clickPath"],
            "access": acct["access"], "account": acct["account"]}


def main():
    s, _, d = call("GET", GW + "/v1/deals")
    check("deals: public (no token) 200", s == 200 and isinstance((d or {}).get("data"), list), (s, d))
    s, _, d = call("GET", GW + "/v1/card-applications")
    check("card applications without a token: 401 at the edge", s == 401, (s, d))

    admin = p10.admin_token()
    if not check("operator signs in with MFA (SUPPORT + CATALOG_EDITOR carry commerce:*)", admin is not None):
        return
    perms = p16.claims(admin).get("perms", [])
    check("admin token carries commerce:read / write / decide",
          {"commerce:read", "commerce:write", "commerce:decide"} <= set(perms), perms)
    s, _, product = call("GET", GW + "/v1/catalog/cards/" + SLUG)
    if not check("the P10 catalog card is public", s == 200, (s, product)):
        return
    card = product["id"]

    # ---------------------------------------------------------------- deals: created here, then ranked
    category = "smoke" + uuid.uuid4().hex[:10] + ".online"
    for_card = create_deal(admin, deal_body("For the Saffron card", category, [card], 1000000))
    open_deal = create_deal(admin, deal_body("Any card", category, [], 1000000))
    golden = create_deal(admin, deal_body("Golden", category, [], 7500000))
    s, items = feed(category)
    ids = [x["id"] for x in items]
    if not check("feed (anonymous): NOT EMPTY, exactly the three deals created", s == 200
                 and sorted(ids) == sorted([for_card["id"], open_deal["id"], golden["id"]]), (s, ids)):
        return
    check("feed: ranked by score, the card-only deal last for an anonymous viewer",
          ids[-1] == for_card["id"] and [x["scoreBps"] for x in items] == sorted([x["scoreBps"] for x in items],
                                                                                 reverse=True), items)
    check("feed: no affiliate URL leaks; every deal has a signed /r/ click path",
          all("landingUrl" not in x and x["clickPath"].startswith("/r/") for x in items), items)
    s, _, g = call("GET", GW + "/v1/deals/" + golden["id"])
    check("golden: ₹75,000 at 10 % cap ₹5,000 + 5 % cap ₹2,000 saves ₹7,000", s == 200
          and g["totalSavingsPaise"] == 700000 and [x["savingPaise"] for x in g["lines"]] == [500000, 200000], g)

    # ---------------------------------------------------------------- card-match through portfolio's event
    u1 = p16.fresh_account("smokep17a")
    tok = u1["access"]
    s, _, mine = call("POST", GW + "/v1/cards", {"cardProductId": card, "last4": "4%03d" % (int(time.time()) % 1000),
                                                 "statementDay": 5, "dueOffsetDays": 20, "nickname": "Smoke P17"},
                      bearer(tok, **{"Idempotency-Key": str(uuid.uuid4())}))
    check("user adds the Saffron card in portfolio: 201", s == 201, (s, mine))
    first = wait(lambda: (lambda its: its and its[0]["id"] == for_card["id"] and its[0]["cardMatch"] == "held")(
        feed(category, tok)[1]))
    check("card-match: after portfolio.card.added reaches commerce, the card's deal ranks first (held)", first,
          feed(category, tok)[1][:1])
    _, mine_feed = feed(category, tok)
    check("card-match ranks, never filters: all three deals still listed", len(mine_feed) == 3, len(mine_feed))

    # ---------------------------------------------------------------- the signed redirect
    s, _, detail = call("GET", GW + "/v1/deals/" + for_card["id"], headers=bearer(tok))
    path = detail["deal"]["clickPath"]
    s, h, _ = raw_get(path, {"User-Agent": "smoke-p17"})
    loc = h.get("location", "")
    ok = check("redirect: 302 to the merchant with subid, no-store, no-referrer", s == 302
               and loc.startswith("https://merchant.example/smoke?ref=dexter&subid=")
               and "no-store" in h.get("cache-control", "") and h.get("referrer-policy") == "no-referrer", (s, h))
    click_id = loc.split("subid=")[-1] if ok else None
    altered = path[:-2] + ("A" if path[-2] != "A" else "B") + path[-1]
    s, _, b = raw_get(altered)
    check("an altered link: 400 CLICK_TOKEN_INVALID, no redirect", s == 400 and b"CLICK_TOKEN_INVALID" in b, (s, b))

    # ---------------------------------------------------------------- postbacks: created, replayed, approved
    if not check("COMMERCE_AFFILIATE_SECRET_MOCK is set (signs the postbacks)",
                 bool(os.environ.get("COMMERCE_AFFILIATE_SECRET_MOCK"))):
        return
    ref = "smoke-" + uuid.uuid4().hex
    doc = {"ref": ref, "subid": click_id, "status": "pending", "amountPaise": 250000, "commissionPaise": 10000,
           "occurredAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    s, d = postback(doc)
    check("postback: created", s == 200 and (d or {}).get("outcome") == "created", (s, d))
    s, d = postback(doc)
    check("postback replay: duplicate", s == 200 and (d or {}).get("outcome") == "duplicate", (s, d))
    s, d = postback(dict(doc, status="approved"))
    check("postback approved later: updated", s == 200 and (d or {}).get("outcome") == "updated", (s, d))
    s, d = postback(doc, signature=False)
    check("postback unsigned: 400 POSTBACK_SIGNATURE_INVALID", s == 400 and code(d) == "POSTBACK_SIGNATURE_INVALID",
          (s, d))
    s, d = postback(doc, network="nobody")
    check("postback for an unknown network: 404", s == 404 and code(d) == "NETWORK_UNKNOWN", (s, d))
    row = wait(lambda: (lambda r: r if r and r["clicks"] >= 1 else None)(stats_row(admin, for_card["id"])),
               seconds=40, step=2)
    check("stats (after the 10 s flush): NOT EMPTY for the deal: 1 click, 1 unique, 1 conversion, ₹100 commission",
          row is not None and row["clicks"] == 1 and row["dailyUniques"] == 1 and row["conversions"] == 1
          and row["commissionPaise"] == 10000, row)

    # ---------------------------------------------------------------- a card application, decided by an operator
    app = {}

    def applied():
        s2, h2, d2 = call("POST", GW + "/v1/card-applications", {"cardProductId": card},
                          bearer(tok, **{"Idempotency-Key": str(uuid.uuid4())}))
        if s2 == 201:
            app.update(d2)
            return True
        return False

    if not check("apply for the Saffron card: 201 (once catalog.card.published has reached commerce)",
                 wait(applied, seconds=45, step=1.5), app):
        return
    s, _, d = call("POST", GW + "/v1/card-applications", {"cardProductId": card},
                   bearer(tok, **{"Idempotency-Key": str(uuid.uuid4())}))
    check("a second open application for the card: 409 APPLICATION_EXISTS", s == 409
          and code(d) == "APPLICATION_EXISTS", (s, d))
    s, _, d = call("POST", GW + "/v1/card-applications", {"cardProductId": str(uuid.uuid4())},
                   bearer(tok, **{"Idempotency-Key": str(uuid.uuid4())}))
    check("an unknown card: 400 UNKNOWN_CARD_PRODUCT", s == 400 and "UNKNOWN_CARD_PRODUCT" in json.dumps(d), (s, d))
    s, _, d = call("GET", GW + "/v1/card-applications", headers=bearer(tok))
    check("my applications: NOT EMPTY, the one submitted", s == 200 and [x["id"] for x in d.get("data") or []]
          == [app["id"]], (s, d))
    s, _, q = call("GET", GW + "/v1/admin/commerce/card-applications?limit=100", headers=bearer(admin))
    queue = (q or {}).get("data") or []
    check("operator queue: NOT EMPTY, holds the application", s == 200 and app["id"] in [x["id"] for x in queue],
          (s, len(queue)))
    base = GW + "/v1/admin/commerce/card-applications/" + app["id"] + ":decide"
    s, h, d = call("POST", base, {"decision": "in_review"}, bearer(admin, **{"If-Match": '"1"'}))
    check("decide in_review (If-Match \"1\"): 200, ETag \"2\"", s == 200 and d.get("status") == "in_review"
          and h.get("etag") == '"2"', (s, d))
    s, _, d = call("POST", base, {"decision": "approved", "reason": "Smoke approval"},
                   bearer(admin, **{"If-Match": '"1"'}))
    check("a stale If-Match: 412", s == 412, (s, d))
    s, _, d = call("POST", base, {"decision": "approved", "reason": "Smoke approval"},
                   bearer(admin, **{"If-Match": '"2"'}))
    check("decide approved: 200", s == 200 and d.get("status") == "approved", (s, d))
    s, _, d = call("GET", GW + "/v1/card-applications/" + app["id"], headers=bearer(tok))
    check("the applicant sees approved with a NON-EMPTY history of three",
          s == 200 and d.get("status") == "approved" and [x["status"] for x in d.get("history") or []]
          == ["submitted", "in_review", "approved"], (s, d))

    # ---------------------------------------------------------------- ads: frequency cap, ad-free, the ad click
    s, _, camp = call("POST", GW + "/v1/admin/commerce/campaigns",
                      {"name": "Smoke campaign", "advertiser": "Smoke Ads", "weight": 1000, "frequencyCap": 2,
                       "frequencyWindowSeconds": 3600, "placements": ["dashboard"],
                       "creatives": [{"headline": "Smoke ad", "body": "b",
                                      "clickUrl": "https://advertiser.example/smoke", "weight": 1}]}, bearer(admin))
    if not check("campaign created: 201 with a NON-EMPTY creative list", s == 201 and len(camp.get("creatives") or [])
                 == 1, (s, camp)):
        return
    served = []
    for _ in range(4):
        s, _, d = call("GET", GW + "/v1/ads/serve?placement=dashboard", headers=bearer(tok))
        served.append(d)
    ours = [x for x in served if (x or {}).get("ad") and x["ad"]["campaignId"] == camp["id"]]
    check("frequency cap 2: four serves show the campaign exactly twice", len(ours) == 2,
          [(x or {}).get("reason") or (x or {}).get("ad", {}).get("campaignId") for x in served])
    if ours:
        s, h, _ = raw_get(ours[0]["ad"]["clickPath"])
        check("ad click: 302 to the creative's URL", s == 302 and h.get("location") == "https://advertiser.example/smoke",
              (s, h.get("location")))

    u2 = p16.fresh_account("smokep17b")
    tok2 = u2["access"]
    s, _, d = call("GET", GW + "/v1/ads/serve?placement=home", headers=bearer(tok2))
    check("a FREE account is served (not ad-free)", s == 200 and d.get("reason") != "AD_FREE", (s, d))
    s, _, d, _ = p16.checkout(tok2, "PREMIUM", "pm_mock_success", "KA")
    check("u2 buys PREMIUM: 201", s == 201, (s, d))
    adfree = wait(lambda: (call("GET", GW + "/v1/ads/serve?placement=home", headers=bearer(tok2))[2] or {})
                  .get("reason") == "AD_FREE", seconds=45, step=1.5)
    check("ad-free: billing.entitlements.changed reached commerce, PREMIUM gets no ad", adfree)
    s, _, d = call("GET", GW + "/v1/ads/serve?placement=nowhere")
    check("an unknown placement: 400 PLACEMENT_UNKNOWN", s == 400 and code(d) == "PLACEMENT_UNKNOWN", (s, d))

    # ---------------------------------------------------------------- isolation and access
    s, _, d = call("GET", GW + "/v1/card-applications/" + app["id"], headers=bearer(tok2))
    check("another account's application: 404", s == 404, (s, d))
    s, _, d = call("GET", GW + "/v1/admin/commerce/stats", headers=bearer(tok))
    check("a user token on the admin API: 403", s == 403, (s, d))
    s, _, d = call("GET", GW + "/v1/deals?limit=5", headers={"Authorization": "Bearer not-a-token"})
    check("a public route with a bad token: 401 (the token is optional, not ignorable)", s == 401, (s, d))


if __name__ == "__main__":
    if SETUP_ONLY:
        print(json.dumps(setup()))
        sys.exit(0)
    main()
    print(f"# P17 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
