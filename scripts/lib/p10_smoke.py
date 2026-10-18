#!/usr/bin/env python3
"""P10 API smoke: catalog ingestion through the gateway (:8080). A mock issuer site runs here on 127.0.0.1:18091
(the catalog's one dev host, CATALOG_INGEST_DEV_HOSTS); the seeded admin signs in with the TOTP secret the P05 smoke
saved (.run/admin-totp). Flow: SSRF-refused sources (400) -> a source for the seeded Saffron Millennia page with a new
annual fee -> run (202, the worker crawls: snapshot to SeaweedFS, extraction, diff) -> one review item with the fee
change -> nothing public before approve -> approve: version n+1 publicly, a second approve replays -> the same page
again: unchanged, no item. Admin endpoints refuse anonymous and user callers. Prints PASS/FAIL; exit 1 on failure.

Usage: p10_smoke.py http://localhost:8080
Env: DEMO_PASSWORD_ADMIN, DEMO_PASSWORD_ASHA.
"""
import base64
import hashlib
import hmac
import http.server
import os
import random
import struct
import sys
import threading
import time
import urllib.parse
import uuid

sys.path.insert(0, os.path.dirname(__file__))
if len(sys.argv) < 3:
    sys.argv.append("http://127.0.0.1:8025")  # p04_smoke reads a Mailpit URL at import; P10 sends no mail
import p04_smoke as p4  # noqa: E402

GW = sys.argv[1].rstrip("/")
call, check, code, bearer = p4.call, p4.check, p4.code, p4.bearer
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SECRET_FILE = os.path.join(ROOT, ".run", "admin-totp")
SITE_PORT = 18091
SLUG = "mock-saffron-millennia-cashback"
PAGES = {}


class Site(http.server.BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        body = PAGES.get(urllib.parse.urlparse(self.path).path)
        if body is None:
            self.send_response(404)
            self.end_headers()
            return
        data = body.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


def totp(secret_b32, step):
    key = base64.b32decode(secret_b32 + "=" * (-len(secret_b32) % 8))
    h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    o = h[-1] & 0x0F
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)


def admin_token():
    """Password -> MFA challenge -> TOTP. A step the P05 smoke already used is refused (replay guard), so try the
    current and the next step, then wait for a fresh one (at most 4 codes: below the lockout threshold)."""
    pw = os.environ.get("DEMO_PASSWORD_ADMIN")
    if not pw or not os.path.exists(SECRET_FILE):
        return None
    secret = open(SECRET_FILE).read().strip()
    for attempt in range(3):
        now = int(time.time()) // 30
        for step in ((now, now + 1) if attempt == 0 else (now,)):
            s, _, d = call("POST", GW + "/v1/admin/auth/login", {"email": "admin@demo.in", "password": pw},
                           {"X-Forwarded-For": "198.51.100.%d" % random.randint(1, 254)})
            if s != 200 or not (d or {}).get("mfaToken") or d.get("setup"):
                return None
            s, _, t = call("POST", GW + "/v1/admin/auth/mfa/verify", {"mfaToken": d["mfaToken"],
                                                                       "code": totp(secret, step)})
            if s == 200:
                return t["accessToken"]
        if attempt < 2:
            time.sleep(31 - int(time.time()) % 30)
    return None


def page(name, joining_paise, annual_rupees):
    return ("<!doctype html><html><head><title>%s | Saffron Bank (mock site)</title></head><body><h1>%s</h1>"
            "<ul><li>Joining fee: &#8377;%d + GST</li><li>Annual fee: &#8377;%d + GST</li></ul>"
            "<p>Smoke page %s</p></body></html>") % (name, name, joining_paise // 100, annual_rupees, uuid.uuid4().hex)


def main():
    server = http.server.ThreadingHTTPServer(("127.0.0.1", SITE_PORT), Site)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    s, _, d = call("GET", GW + "/v1/admin/review")
    check("review queue without a token: 401 at the edge", s == 401, (s, d))
    pw = os.environ.get("DEMO_PASSWORD_ASHA")
    if pw:
        s, _, t = call("POST", GW + "/v1/auth/login", {"email": "asha@demo.in", "password": pw},
                       {"X-Forwarded-For": "198.51.100.%d" % random.randint(1, 254)})
        if s == 200:
            s, _, d = call("GET", GW + "/v1/admin/ingestion/sources", headers=bearer(t["accessToken"]))
            check("ingestion sources with a user token: 403", s == 403, (s, d))

    tok = admin_token()
    if not check("admin signs in with MFA (secret saved by the P05 smoke)", tok is not None,
                 "no %s or the admin was enrolled earlier (reseed to rerun)" % SECRET_FILE):
        return
    H = bearer(tok)

    s, _, card = call("GET", GW + "/v1/catalog/cards/" + SLUG)
    if not check("seeded card is public", s == 200, (s, card)):
        return
    v0, fee0 = card["version"], card["annualFeePaise"]
    fee = random.choice([r for r in range(1101, 4999, 7) if r * 100 != fee0])

    for url, why in (("https://localhost/cards", "HOST_NOT_ALLOWED"),
                     ("https://169.254.169.254/latest/meta-data/", "HOST_NOT_ALLOWED"),
                     ("http://www.hdfcbank.com/cards", "SCHEME_NOT_ALLOWED")):
        s, _, d = call("POST", GW + "/v1/admin/ingestion/sources", {"issuerCode": card["issuerCode"], "url": url,
                                                                    "kind": "html"}, H)
        errs = (d or {}).get("errors") or [{}]
        check("source %s refused: 400 %s" % (url, why), s == 400 and errs[0].get("code") == why, (s, d))

    path = "/cards/%s-%s" % (SLUG, uuid.uuid4().hex[:8])
    PAGES[path] = page(card["name"], card["joiningFeePaise"], fee)
    s, h, src = call("POST", GW + "/v1/admin/ingestion/sources", {"issuerCode": card["issuerCode"], "cardProductId":
                     card["id"], "url": "http://127.0.0.1:%d%s" % (SITE_PORT, path), "kind": "html"}, H)
    if not check("source for the mock issuer page: 201 + ETag", s == 201 and h.get("etag") == '"0"', (s, src)):
        return

    def crawl():
        s, h, r = call("POST", GW + "/v1/admin/ingestion/sources/%s/runs" % src["id"], None, H)
        if s != 202:
            return s, r
        for _ in range(60):
            time.sleep(1)
            s, _, r = call("GET", GW + "/v1/admin/ingestion/runs/" + r["id"], headers=H)
            if s == 200 and r["status"] in ("completed", "failed"):
                break
        return s, r

    s, run = crawl()
    stats = ((run or {}).get("checkpoint") or {}).get("stats", {})
    check("run 202 -> worker completes: snapshot stored, one review item",
          s == 200 and run["status"] == "completed" and stats.get("snapshots") == 1 and stats.get("reviewItems") == 1,
          (s, run))

    item = None
    cursor = None
    for _ in range(20):
        q = "/v1/admin/review?status=pending&limit=100" + ("&cursor=" + urllib.parse.quote(cursor) if cursor else "")
        s, _, d = call("GET", GW + q, headers=H)
        if s != 200:
            break
        item = next((i for i in d["data"] if i["cardProductId"] == card["id"]), item)
        cursor = d["page"]["nextCursor"]
        if item or not cursor:
            break
    changes = (item or {}).get("changes") or []
    check("review item: annual fee %d -> %d against v%d" % (fee0, fee * 100, v0),
          len(changes) == 1 and changes[0]["path"] == "annualFeePaise" and changes[0]["from"] == fee0
          and changes[0]["to"] == fee * 100 and item["baseVersion"] == v0, item)
    s, _, d = call("GET", GW + "/v1/catalog/cards/" + SLUG)
    check("nothing public before approve", s == 200 and d["version"] == v0 and d["annualFeePaise"] == fee0, (s, d))
    if not item:
        return

    s, _, a1 = call("POST", GW + "/v1/admin/review/%s:approve" % item["id"], None, H)
    check("approve: 200, the promoter publishes v%d" % (v0 + 1),
          s == 200 and a1["newVersion"] == v0 + 1 and a1["replayed"] is False, (s, a1))
    s, _, a2 = call("POST", GW + "/v1/admin/review/%s:approve" % item["id"], None, H)
    check("approve again: same version, replayed", s == 200 and a2["newVersion"] == v0 + 1 and a2["replayed"] is True,
          (s, a2))
    s, h, d = call("GET", GW + "/v1/catalog/cards/" + SLUG)
    check("public detail: v%d, ETag, new annual fee" % (v0 + 1), s == 200 and d["version"] == v0 + 1
          and h.get("etag") == '"%d"' % (v0 + 1) and d["annualFeePaise"] == fee * 100, (s, h.get("etag"), d))

    s, run = crawl()
    stats = ((run or {}).get("checkpoint") or {}).get("stats", {})
    check("same page again: unchanged (content hash), no review item",
          s == 200 and run["status"] == "completed" and stats.get("unchanged") == 1 and not stats.get("reviewItems"),
          (s, run))
    s, _, _ = call("DELETE", GW + "/v1/admin/ingestion/sources/" + src["id"], headers=H)
    check("delete the smoke source: 204", s == 204, s)
    server.shutdown()


if __name__ == "__main__":
    main()
    print(f"# P10 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
