#!/usr/bin/env python3
"""P09 API smoke: the public catalog through the gateway (:8080) on the seed-small catalog (40 MOCK_ cards, versioned
rules), without a token: keyset paging, filters, detail with ETag, search, category potential, offers;
admin endpoints refuse anonymous and user callers. Prints PASS/FAIL lines; exit 1 on any failure.

Usage: p09_smoke.py http://localhost:8080
Env: DEMO_PASSWORD_ASHA.
"""
import os
import random
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(__file__))
if len(sys.argv) < 3:
    sys.argv.append("http://127.0.0.1:8025")  # p04_smoke reads a Mailpit URL at import; P09 sends no mail
import p04_smoke as p4  # noqa: E402

GW = sys.argv[1].rstrip("/")
call, check, code, bearer = p4.call, p4.check, p4.code, p4.bearer


def main():
    # ---- list: 40 seeded published cards, name order, no token
    seen, names, cursor, pages = [], [], None, 0
    while True:
        q = "/v1/catalog/cards?limit=15" + ("&cursor=" + urllib.parse.quote(cursor) if cursor else "")
        s, h, d = call("GET", GW + q)
        if not check("catalog page %d: 200 without a token" % (pages + 1), s == 200, (s, d)):
            return
        seen += [c["id"] for c in d["data"]]
        names += [c["name"] for c in d["data"]]
        pages += 1
        cursor = d["page"]["nextCursor"]
        if not cursor or pages > 10:
            break
    # (name order is the database collation's; the IT checks it on plain names)
    check("walk: 40 seeded cards, no repeats, 3 pages", len(seen) == 40 and len(set(seen)) == 40 and pages == 3,
          (len(seen), pages))
    s, h, d = call("GET", GW + "/v1/catalog/cards?network=visa&limit=50")
    check("network filter: only visa", s == 200 and d["data"] and all(c["network"] == "visa" for c in d["data"]), (s,))
    s, h, d = call("GET", GW + "/v1/catalog/cards?maxAnnualFee=0&limit=50")
    check("maxAnnualFee=0: only lifetime-free cards", s == 200 and all(c["annualFeePaise"] == 0 for c in d["data"]), (s,))
    s, h, d = call("GET", GW + "/v1/catalog/cards?category=food.delivery&minRateBps=500&limit=50")
    check("category earning ≥ 5 % on food.delivery: 200, some cards", s == 200 and len(d["data"]) > 0, (s, d))

    # ---- detail, search, potential, offers
    slug = "mock-saffron-millennia-cashback"
    s, h, d = call("GET", GW + "/v1/catalog/cards/" + slug)
    check("detail: 200, ETag = version, rules + milestone", s == 200 and h.get("etag") == '"%d"' % d.get("version", -1)
          and len(d.get("rules", [])) == 3 and d.get("milestone"), (s, h.get("etag"), d))
    s, h, d = call("GET", GW + "/v1/catalog/cards/no-such-card")
    check("unknown slug: 404 NOT_FOUND", s == 404 and code(d) == "NOT_FOUND", (s, d))
    s, h, d = call("GET", GW + "/v1/catalog/cards/search?q=saffron")
    check("search q=saffron: trigram hits, Saffron cards first",
          s == 200 and d["data"] and "Saffron" in d["data"][0]["name"], (s, d))
    s, h, d = call("GET", GW + "/v1/catalog/categories/potential?category=food.delivery")
    rates = [c["rateBps"] for c in (d or {}).get("cards", [])]
    check("potential food.delivery: ≤ 10 cards, best rate first", s == 200 and 0 < len(rates) <= 10
          and rates == sorted(rates, reverse=True), (s, d))
    s, h, d = call("GET", GW + "/v1/catalog/offers?category=food.delivery")
    check("offers: 200 (active only; none seeded)", s == 200 and isinstance(d.get("data"), list), (s, d))

    # ---- admin is not public
    s, h, d = call("GET", GW + "/v1/admin/catalog/cards")
    check("admin catalog without a token: 401 at the edge", s == 401, (s, d))
    pw = os.environ.get("DEMO_PASSWORD_ASHA")
    if pw:
        s, h, t = call("POST", GW + "/v1/auth/login", {"email": "asha@demo.in", "password": pw},
                       {"X-Forwarded-For": "198.51.100.%d" % random.randint(1, 254)})
        if check("asha signs in", s == 200, s):
            s, h, d = call("GET", GW + "/v1/admin/catalog/cards", headers=bearer(t["accessToken"]))
            check("admin catalog with a user token: 403", s == 403, (s, d))


if __name__ == "__main__":
    main()
    print(f"# P09 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
