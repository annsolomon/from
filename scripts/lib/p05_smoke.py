#!/usr/bin/env python3
"""P05 API smoke against the running identity service and Mailpit: user TOTP enrolment -> MFA sign-in (replay
refused) -> recovery code (single use) -> step-up -> disable; login throttle (429 + Retry-After); the seeded admin's
first console sign-in (TOTP setup), roles -> permissions, audited account lookup, permission denial, break-glass
(SUPER_ADMIN for <= 1 h, token capped), admin invite -> accept -> first sign-in, ending the elevation.

Usage: p05_smoke.py http://localhost:8081 http://127.0.0.1:8025
Env: DEMO_PASSWORD_ADMIN (seeded admin@demo.in).
"""
import base64
import hashlib
import hmac
import json
import os
import struct
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(__file__))
import p04_smoke as p4  # noqa: E402  (call/check/mail_token helpers)

ID = sys.argv[1].rstrip("/")
p4.MAIL = sys.argv[2].rstrip("/")
PASSWORD = "Smoke-Test-Password-42"
call, check, code, bearer = p4.call, p4.check, p4.code, p4.bearer


def totp(secret_b32, offset=0):
    key = base64.b32decode(secret_b32 + "=" * (-len(secret_b32) % 8))
    step = int(time.time()) // 30 + offset
    h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    o = h[-1] & 0x0F
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1000000)


ADMIN_TOTP_FILE = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                               ".run", "admin-totp")


def keep_secret(secret):
    """The demo admin's TOTP secret exists only at its first sign-in: later smokes (P10 catalog review) sign in
    with it. Dev only, in the git-ignored .run/ (mode 600)."""
    if not secret:
        return
    os.makedirs(os.path.dirname(ADMIN_TOTP_FILE), exist_ok=True)
    fd = os.open(ADMIN_TOTP_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(secret)


def claims(jwt):
    part = jwt.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


def new_user():
    email = f"smoke5-{uuid.uuid4().hex[:10]}@smoke.test"
    call("POST", ID + "/v1/auth/register", {"email": email, "password": PASSWORD, "displayName": "Smoke5"},
         {"Idempotency-Key": str(uuid.uuid4())})
    tok = p4.mail_token(email, "Verify", "verify")
    call("POST", ID + "/v1/auth/verify-email", {"token": tok})
    s, _, t = call("POST", ID + "/v1/auth/login", {"email": email, "password": PASSWORD})
    return email, (t or {}).get("accessToken")


def user_mfa():
    email, access = new_user()
    if not check("user signed up and in", access is not None):
        return
    s, h, e = call("POST", ID + "/v1/me/mfa/totp/enroll", headers=bearer(access))
    ok = check("enroll: 200 secret + otpauth URI, no-store",
               s == 200 and (e or {}).get("otpauthUri", "").startswith("otpauth://totp/")
               and "no-store" in h.get("cache-control", ""), (s, e))
    if not ok:
        return
    secret = e["secret"]
    first = totp(secret)
    s, _, d = call("POST", ID + "/v1/me/mfa/totp/confirm", {"code": first}, bearer(access))
    codes = (d or {}).get("recoveryCodes", [])
    check("confirm: 200 and 10 recovery codes", s == 200 and len(codes) == 10, (s, d))
    s, _, d = call("POST", ID + "/v1/auth/login", {"email": email, "password": PASSWORD})
    check("login with MFA: challenge, no tokens", s == 200 and (d or {}).get("mfaRequired") is True
          and "accessToken" not in d, (s, d))
    mt = (d or {}).get("mfaToken", "")
    s, _, d = call("POST", ID + "/v1/auth/mfa/verify", {"mfaToken": mt, "code": first})
    check("replayed TOTP step: 401 INVALID_MFA_CODE", s == 401 and code(d) == "INVALID_MFA_CODE", (s, d))
    s, _, d = call("POST", ID + "/v1/auth/mfa/verify", {"mfaToken": mt, "code": totp(secret, 1)})
    ok = check("next TOTP step: 200 tokens, amr has mfa", s == 200 and "mfa" in claims(d["accessToken"]).get("amr", []),
               (s, d))
    if not ok:
        return
    mfa_access = d["accessToken"]
    s, _, d = call("POST", ID + "/v1/auth/login", {"email": email, "password": PASSWORD})
    mt2 = (d or {}).get("mfaToken", "")
    s, _, d = call("POST", ID + "/v1/auth/mfa/verify", {"mfaToken": mt2, "recoveryCode": codes[0]})
    check("recovery code: 200", s == 200, (s, d))
    s, _, d = call("POST", ID + "/v1/auth/login", {"email": email, "password": PASSWORD})
    s, _, d = call("POST", ID + "/v1/auth/mfa/verify", {"mfaToken": (d or {}).get("mfaToken", ""),
                                                        "recoveryCode": codes[0]})
    check("same recovery code again: 401", s == 401 and code(d) == "INVALID_MFA_CODE", (s, d))
    s, _, d = call("POST", ID + "/v1/auth/step-up", {"password": PASSWORD, "recoveryCode": codes[1]},
                   bearer(mfa_access))
    check("step-up (password + recovery code): 200 fresh token", s == 200 and (d or {}).get("accessToken"), (s, d))
    s, _, d = call("DELETE", ID + "/v1/me/mfa/totp", headers=bearer(access))
    check("pre-MFA token cannot remove MFA: 401 STEP_UP_REQUIRED", s == 401 and code(d) == "STEP_UP_REQUIRED", (s, d))
    s, _, _ = call("DELETE", ID + "/v1/me/mfa/totp", headers=bearer(mfa_access))
    check("MFA session removes MFA: 204", s == 204, s)


def throttle():
    ghost = f"ghost-{uuid.uuid4().hex[:8]}@smoke.test"
    seen = None
    for _ in range(8):
        s, h, d = call("POST", ID + "/v1/auth/login", {"email": ghost, "password": "Not-The-Password-1"})
        if s == 429:
            seen = (h.get("retry-after"), code(d))
            break
    check("repeated failures are throttled: 429 RATE_LIMITED + Retry-After",
          seen is not None and seen[0] and seen[1] == "RATE_LIMITED", seen)


def admin():
    pw = os.environ.get("DEMO_PASSWORD_ADMIN")
    if not check("DEMO_PASSWORD_ADMIN set", bool(pw)):
        return
    s, _, d = call("POST", ID + "/v1/admin/auth/login", {"email": "admin@demo.in", "password": pw})
    if not check("admin login: 200 challenge", s == 200 and (d or {}).get("mfaToken"), (s, d)):
        return
    if not d.get("setup"):
        check("admin TOTP setup at first sign-in (skipped: already enrolled; reseed to rerun)", True)
        return
    mt = d["mfaToken"]
    s, _, st = call("POST", ID + "/v1/admin/auth/mfa/setup", {"mfaToken": mt})
    check("admin MFA setup: secret + otpauth URI", s == 200 and (st or {}).get("secret"), (s, st))
    secret = (st or {}).get("secret", "")
    keep_secret(secret)
    s, _, t = call("POST", ID + "/v1/admin/auth/mfa/verify", {"mfaToken": mt, "code": totp(secret)})
    if not check("admin verify: 200 admin tokens", s == 200 and (t or {}).get("accessToken"), (s, t)):
        return
    c = claims(t["accessToken"])
    check("admin token: scope admin, standing roles, permissions, amr mfa",
          c.get("scope") == "admin" and "SUPER_ADMIN" not in c.get("roles", []) and "accounts:read" in c.get("perms", [])
          and "mfa" in c.get("amr", []), c)
    access = t["accessToken"]
    s, _, d = call("GET", ID + "/v1/admin/accounts?email=asha@demo.in", headers=bearer(access))
    check("account lookup (audited): 200 asha", s == 200 and (d or {}).get("email") == "asha@demo.in", (s, d))
    s, _, d = call("GET", ID + "/v1/admin/admins", headers=bearer(access))
    check("admins list without break-glass: 403", s == 403, (s, d))
    s, _, d = call("GET", ID + "/v1/me", headers=bearer(access))
    check("admin token on a user endpoint: 403", s == 403, (s, d))
    s, _, e = call("POST", ID + "/v1/admin/elevations",
                   {"reason": "Smoke test: verify break-glass end to end", "code": totp(secret, 1)}, bearer(access))
    ok = check("break-glass: 200 SUPER_ADMIN token capped at the elevation",
               s == 200 and "SUPER_ADMIN" in claims(e["accessToken"]).get("roles", [])
               and claims(e["accessToken"])["exp"] <= _epoch(e["expiresAt"]), (s, e))
    if not ok:
        return
    sup = e["accessToken"]
    s, _, d = call("GET", ID + "/v1/admin/admins?limit=10", headers=bearer(sup))
    check("admins list while elevated: 200", s == 200 and isinstance((d or {}).get("data"), list), (s, d))
    invitee = f"smoke-admin-{uuid.uuid4().hex[:8]}@smoke.test"
    s, _, d = call("POST", ID + "/v1/admin/admins", {"email": invitee, "roles": ["SUPPORT"]}, bearer(sup))
    check("invite admin: 201 invited", s == 201 and (d or {}).get("status") == "invited", (s, d))
    tok = p4.mail_token(invitee, "invited", "invite")
    check("invite email arrived in Mailpit", tok is not None)
    if tok:
        s, _, d = call("POST", ID + "/v1/admin/auth/invite/accept", {"token": tok, "password": PASSWORD})
        check("accept invite: 200", s == 200, (s, d))
        s, _, d = call("POST", ID + "/v1/admin/auth/login", {"email": invitee, "password": PASSWORD})
        check("invited admin signs in: setup required", s == 200 and (d or {}).get("setup") is True, (s, d))
    s, _, d = call("DELETE", ID + "/v1/admin/elevations/current", headers=bearer(sup))
    check("end break-glass: 200", s == 200 and (d or {}).get("ended") is True, (s, d))
    s, _, d = call("GET", ID + "/v1/admin/admins", headers=bearer(sup))
    check("old SUPER_ADMIN token refused at once: 403", s == 403, (s, d))


def _epoch(iso):
    from datetime import datetime
    return int(datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp())


if __name__ == "__main__":
    user_mfa()
    throttle()
    admin()
    print(f"# P05 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
