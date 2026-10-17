#!/usr/bin/env python3
"""P04 API smoke against the running identity service (dev profile) and Mailpit: register (Idempotency-Key) ->
verification email -> verify -> login -> /me + PATCH (If-Match) -> refresh rotation + reuse detection -> sessions ->
logout, enumeration-safe answers, the seeded demo login, client-credentials tokens and a signing-key rotation.
Prints PASS/FAIL lines; exit 1 on any failure.

Usage: p04_smoke.py http://localhost:8081 http://127.0.0.1:8025
Env: DEMO_PASSWORD_ASHA (seeded demo login), SERVICE_CLIENT_SECRET (client credentials).
"""
import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ID = sys.argv[1].rstrip("/")
MAIL = sys.argv[2].rstrip("/")
FAILS = []
PASSWORD = "Smoke-Test-Password-42"


def call(method, url, body=None, headers=None, form=None, timeout=20, content_type="application/json"):
    h = {"Accept": "application/json", "X-Request-Id": "smoke-p04-" + uuid.uuid4().hex[:8]}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = content_type
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        h["Content-Type"] = "application/x-www-form-urlencoded"
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status, hdrs, raw = r.status, r.headers, r.read().decode()
    except urllib.error.HTTPError as e:
        status, hdrs, raw = e.code, e.headers, e.read().decode()
    except Exception as e:  # noqa: BLE001 - any transport error is a failed call
        return 0, {}, {"transport_error": str(e)}
    hd = {k.lower(): v for k, v in hdrs.items()}
    try:
        parsed = json.loads(raw) if raw else None
    except ValueError:
        parsed = raw
    return status, hd, parsed


def check(label, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + label + ("" if ok else "  " + str(detail)[:240]))
    if not ok:
        FAILS.append(label)
    return ok


def code(d):
    return d.get("code") if isinstance(d, dict) else None


def bearer(tok, **more):
    h = {"Authorization": "Bearer " + tok}
    h.update(more)
    return h


def mail_token(to, subject_part, kind, seconds=20):
    """Polls Mailpit for the newest message to `to` whose subject contains subject_part; returns the link token."""
    end = time.time() + seconds
    while time.time() < end:
        s, _, d = call("GET", MAIL + "/api/v1/messages?limit=100")
        if s == 200 and isinstance(d, dict):
            for m in d.get("messages") or []:
                tos = [t.get("Address", "").lower() for t in (m.get("To") or [])]
                if to.lower() in tos and subject_part in (m.get("Subject") or ""):
                    s2, _, full = call("GET", MAIL + "/api/v1/message/" + m["ID"])
                    text = (full or {}).get("Text", "") if isinstance(full, dict) else ""
                    found = re.search("#" + kind + "=([A-Za-z0-9_-]{43})", text)
                    if found:
                        return found.group(1)
        time.sleep(0.5)
    return None


def main():
    s, h, d = call("GET", ID + "/.well-known/jwks.json")
    check("jwks: 200, >= 2 public keys, no private parts",
          s == 200 and len((d or {}).get("keys", [])) >= 2 and '"d"' not in json.dumps(d), (s, d))

    email = f"smoke-{uuid.uuid4().hex[:10]}@smoke.test"
    key = str(uuid.uuid4())
    reg = {"email": email, "password": PASSWORD, "displayName": "Smoke"}
    s, h, d = call("POST", ID + "/v1/auth/register", reg, {"Idempotency-Key": key})
    check("register: 202 accepted", s == 202 and (d or {}).get("status") == "accepted", (s, d))
    s, h, d = call("POST", ID + "/v1/auth/register", reg, {"Idempotency-Key": key})
    check("register replay: 202 + Idempotent-Replayed", s == 202 and h.get("idempotent-replayed") == "true", (s, h))
    s, _, d = call("POST", ID + "/v1/auth/register", reg)
    check("register without key: 400 IDEMPOTENCY_KEY_REQUIRED", s == 400 and code(d) == "IDEMPOTENCY_KEY_REQUIRED", d)
    s, _, d = call("POST", ID + "/v1/auth/register", dict(reg, password="password123"),
                   {"Idempotency-Key": str(uuid.uuid4())})
    check("register breached password: 400", s == 400 and "BREACHED" in json.dumps(d), d)

    s, _, d = call("POST", ID + "/v1/auth/login", {"email": email, "password": PASSWORD})
    check("login before verification: 403 EMAIL_NOT_VERIFIED", s == 403 and code(d) == "EMAIL_NOT_VERIFIED", (s, d))

    tok = mail_token(email, "Verify", "verify")
    check("verification email arrived in Mailpit", tok is not None)
    if tok:
        s, _, d = call("POST", ID + "/v1/auth/verify-email", {"token": tok})
        check("verify-email: 200 verified", s == 200 and (d or {}).get("status") == "verified", (s, d))
        s, _, d = call("POST", ID + "/v1/auth/verify-email", {"token": tok})
        check("verify-email reused: 400 INVALID_TOKEN", s == 400 and code(d) == "INVALID_TOKEN", d)

    s, h, t = call("POST", ID + "/v1/auth/login", {"email": email, "password": PASSWORD, "deviceLabel": "smoke"})
    ok = check("login: 200 tokens, no-store", s == 200 and isinstance(t, dict) and t.get("accessToken")
               and t.get("refreshToken") and "no-store" in h.get("cache-control", ""), (s, t))
    if not ok:
        return
    access, refresh = t["accessToken"], t["refreshToken"]
    s, h, me = call("GET", ID + "/v1/me", headers=bearer(access))
    check("me: 200 with ETag", s == 200 and (me or {}).get("email") == email and h.get("etag"), (s, me))
    etag = h.get("etag")
    merge = "application/merge-patch+json"
    s, h, d = call("PATCH", ID + "/v1/me", {"phone": "+91 98123 00042"}, bearer(access, **{"If-Match": etag}),
                   content_type=merge)
    check("PATCH /me with If-Match: 200, phone normalised, new ETag",
          s == 200 and (d or {}).get("phone") == "+919812300042" and h.get("etag") not in (None, etag), (s, d))
    s, _, d = call("PATCH", ID + "/v1/me", {"displayName": "stale"}, bearer(access, **{"If-Match": etag}),
                   content_type=merge)
    check("PATCH /me with a stale ETag: 412 VERSION_CONFLICT", s == 412 and code(d) == "VERSION_CONFLICT", (s, d))

    s, _, r1 = call("POST", ID + "/v1/auth/token/refresh", {"refreshToken": refresh})
    check("refresh: 200, rotated", s == 200 and r1.get("refreshToken") not in (None, refresh), (s, r1))
    s, _, d = call("POST", ID + "/v1/auth/token/refresh", {"refreshToken": refresh})
    check("replay of the rotated refresh token: 401 REFRESH_TOKEN_REUSED", s == 401 and code(d) == "REFRESH_TOKEN_REUSED",
          (s, d))
    s, _, d = call("POST", ID + "/v1/auth/token/refresh", {"refreshToken": (r1 or {}).get("refreshToken", "")})
    check("family revoked: the newest token is dead too (401)", s == 401, (s, d))

    s, _, t2 = call("POST", ID + "/v1/auth/login", {"email": email, "password": PASSWORD})
    s, _, ses = call("GET", ID + "/v1/me/sessions?limit=50", headers=bearer(t2["accessToken"]))
    data = (ses or {}).get("data", [])
    check("sessions: only the new session is open, marked current",
          s == 200 and len(data) == 1 and data[0].get("current") is True and "hasMore" in ses.get("page", {}), (s, ses))

    wrong = call("POST", ID + "/v1/auth/login", {"email": email, "password": "Not-The-Password-1"})
    unknown = call("POST", ID + "/v1/auth/login", {"email": "nobody-" + uuid.uuid4().hex[:6] + "@smoke.test",
                                                   "password": "Not-The-Password-1"})
    check("unknown email and wrong password answer identically (401 INVALID_CREDENTIALS)",
          wrong[0] == unknown[0] == 401 and code(wrong[2]) == code(unknown[2]) == "INVALID_CREDENTIALS"
          and wrong[2].get("detail") == unknown[2].get("detail"), (wrong, unknown))
    a = call("POST", ID + "/v1/auth/password/forgot", {"email": email})
    b = call("POST", ID + "/v1/auth/password/forgot", {"email": "nobody@smoke.test"})
    check("forgot password: 202 for known and unknown emails", a[0] == b[0] == 202 and a[2] == b[2], (a, b))

    pw = os.environ.get("DEMO_PASSWORD_ASHA")
    if pw:
        s, _, d = call("POST", ID + "/v1/auth/login", {"email": "asha@demo.in", "password": pw})
        check("seeded demo account asha@demo.in signs in", s == 200 and (d or {}).get("accessToken"), (s, d))
    else:
        check("seeded demo account asha@demo.in signs in", False, "DEMO_PASSWORD_ASHA not set")

    secret = os.environ.get("SERVICE_CLIENT_SECRET")
    if secret:
        basic = "Basic " + base64.b64encode(f"ledger:{urllib.parse.quote(secret, safe='')}".encode()).decode()
        s, _, d = call("POST", ID + "/oauth2/token", headers={"Authorization": basic},
                       form={"grant_type": "client_credentials", "audience": "catalog"})
        check("client credentials ledger -> catalog: 200 Bearer, 300 s",
              s == 200 and (d or {}).get("token_type") == "Bearer" and d.get("expires_in") == 300, (s, d))
        bad = "Basic " + base64.b64encode(b"ledger:wrong").decode()
        s, _, d = call("POST", ID + "/oauth2/token", headers={"Authorization": bad},
                       form={"grant_type": "client_credentials", "audience": "catalog"})
        check("client credentials with a wrong secret: 401 INVALID_CLIENT", s == 401 and code(d) == "INVALID_CLIENT", d)
    else:
        check("client credentials", False, "SERVICE_CLIENT_SECRET not set")

    before = t2["accessToken"]
    s, _, k1 = call("GET", ID + "/_dev/p04/keys")
    s, _, k2 = call("POST", ID + "/_dev/p04/keys/rotate")
    check("dev key rotation: new active kid", s == 200 and k1 and k2 and k1.get("activeKid") != k2.get("activeKid"),
          (k1, k2))
    s, _, d = call("GET", ID + "/v1/me", headers=bearer(before))
    check("token signed before the rotation is still accepted", s == 200, (s, d))

    s, _, _ = call("POST", ID + "/v1/auth/logout", headers=bearer(before))
    check("logout: 204", s == 204, s)
    s, _, d = call("POST", ID + "/v1/auth/token/refresh", {"refreshToken": t2["refreshToken"]})
    check("refresh after logout: 401", s == 401, (s, d))


if __name__ == "__main__":
    main()
    print(f"# P04 smoke: {'PASS' if not FAILS else 'FAIL (' + str(len(FAILS)) + ')'}")
    sys.exit(1 if FAILS else 0)
