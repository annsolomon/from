#!/usr/bin/env python3
"""P06 API smoke against the running gateway (:8080) in front of the real services: route table on live identity
(JWKS, demo sign-in, /v1/me with ETag through the gateway), request-id echo, header hygiene, public vs token routes,
404 for unknown paths, body-size 413, security headers, auth-class rate limit (429 + RateLimit headers), the
admin route, and the dev route. Prints PASS/FAIL lines; exit 1 on any failure.

Usage: p06_smoke.py http://localhost:8080
Env: DEMO_PASSWORD_ASHA.
"""
import os
import random
import sys
import uuid

sys.path.insert(0, os.path.dirname(__file__))
import p04_smoke as p4  # noqa: E402

GW = sys.argv[1].rstrip("/")
call, check, code = p4.call, p4.check, p4.code


def ip():
    return "198.51.100.%d" % random.randint(1, 254)


def raw_post(base, path, size):
    """Raw HTTP/1.1 POST with a declared size; reads the status line even if the gateway answers early."""
    import socket
    from urllib.parse import urlparse
    u = urlparse(base)
    so = socket.create_connection((u.hostname, u.port or 80), timeout=20)
    try:
        so.sendall(("POST %s HTTP/1.1\r\nHost: %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\n"
                    "X-Forwarded-For: %s\r\n\r\n" % (path, u.hostname, size, ip())).encode())
        try:
            chunk = b"a" * 16384
            for _ in range(0, size, len(chunk)):
                so.sendall(chunk)
        except OSError:
            pass
        return so.makefile("rb").readline().decode(errors="replace").strip()
    except OSError as e:
        return "transport error " + str(e)
    finally:
        so.close()


def main():
    s, h, d = call("GET", GW + "/.well-known/jwks.json")
    check("jwks through the gateway (public): 200 EC keys", s == 200 and '"EC"' in str(d), (s, d))
    rid = "smoke-p06-" + uuid.uuid4().hex[:8]
    s, h, d = call("GET", GW + "/v1/nope", headers={"X-Request-Id": rid})
    check("unknown path: 404 NOT_FOUND problem, request id echoed",
          s == 404 and code(d) == "NOT_FOUND" and h.get("x-request-id") == rid and d.get("requestId") == rid, (s, h, d))
    for hdr, val in (("x-content-type-options", "nosniff"), ("referrer-policy", "no-referrer"),
                     ("x-frame-options", "DENY")):
        check(f"security header {hdr}", h.get(hdr) == val, h)
    check("CSP default-src 'none'", h.get("content-security-policy", "").startswith("default-src 'none'"), h)

    s, h, d = call("GET", GW + "/v1/me")
    check("token route without a token: 401 UNAUTHENTICATED + WWW-Authenticate",
          s == 401 and code(d) == "UNAUTHENTICATED" and h.get("www-authenticate", "").startswith("Bearer"), (s, d))
    s, h, d = call("GET", GW + "/v1/expenses")
    check("ledger route without a token: 401 at the edge", s == 401, (s, d))
    s, h, d = call("GET", GW + "/v1/me", headers={"Authorization": "Bearer not.a.jwt"})
    check("garbage token: 401", s == 401, (s, d))

    pw = os.environ.get("DEMO_PASSWORD_ASHA")
    if not check("DEMO_PASSWORD_ASHA set", bool(pw)):
        return
    s, h, t = call("POST", GW + "/v1/auth/login", {"email": "asha@demo.in", "password": pw},
                   {"X-Forwarded-For": ip()})
    ok = check("demo sign-in through the gateway (public route): 200 tokens + RateLimit headers",
               s == 200 and (t or {}).get("accessToken") and h.get("ratelimit-policy") == '"auth";q=10;w=60', (s, h, t))
    if not ok:
        return
    tok = t["accessToken"]
    s, h, d = call("GET", GW + "/v1/me", headers={"Authorization": "Bearer " + tok, "X-Account-Id": str(uuid.uuid4()),
                                                 "X-Request-Id": rid})
    check("/v1/me through the gateway: asha, ETag, spoofed X-Account-Id ignored",
          s == 200 and (d or {}).get("email") == "asha@demo.in" and h.get("etag") and h.get("x-request-id") == rid,
          (s, d))
    check("RateLimit on a read: read class 600/min", h.get("ratelimit-policy") == '"read";q=600;w=60', h)
    s, h, d = call("GET", GW + "/v1/expenses", headers={"Authorization": "Bearer " + tok})
    check("ledger route with a token reaches ledger (no expenses API before P07: its own 404 problem)",
          s == 404 and code(d) == "NOT_FOUND" and d.get("instance") == "/v1/expenses", (s, d))
    s, h, d = call("GET", GW + "/v1/admin/admins", headers={"Authorization": "Bearer " + tok})
    check("admin route with a user token: identity answers 403", s == 403, (s, d))

    status_line = raw_post(GW, "/v1/auth/login", 300 * 1024)
    check("body over 256 KB: 413 at the edge", " 413" in status_line, status_line)

    one = ip()
    statuses = []
    for _ in range(11):
        s, h, d = call("POST", GW + "/v1/auth/login",
                       {"email": "rl-%s@smoke.test" % uuid.uuid4().hex[:6], "password": "Not-The-Password-1"},
                       {"X-Forwarded-For": one})
        statuses.append(s)
    check("auth class: 10 per minute per IP, the 11th is 429 RATE_LIMITED + Retry-After",
          statuses[:10] == [401] * 10 and statuses[10] == 429 and code(d) == "RATE_LIMITED" and h.get("retry-after"),
          (statuses, h, d))

    s, h, d = call("GET", GW + "/_dev/p04/keys")
    check("dev route /_dev/p04/keys (dev profile): 200 through the gateway", s == 200 and (d or {}).get("activeKid"),
          (s, d))


if __name__ == "__main__":
    main()
    print(f"# P06 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
