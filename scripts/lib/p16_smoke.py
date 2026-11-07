#!/usr/bin/env python3
"""P16 API smoke: billing through the gateway (:8080) with the mock payment provider.

The P15 lesson is the design rule here: an identity asserted over an empty collection is not a test. So nothing is
read from seeded state. Every account is REGISTERED by this script (Mailpit verifies it), every subscription and
invoice is CREATED by this script, and every collection is asserted NOT EMPTY before anything is said about its
contents. The parts that travel - the provider's webhooks over real HTTP, billing's outbox over Kafka to identity -
are waited for and then asserted, never assumed:

  plans (public) -> a decline -> a checkout (201, replay, 422 on another body) -> a second checkout refused ->
  invoices with the GST lines adding up -> entitlements PREMIUM -> identity puts PREMIUM in a REFRESHED token
  (billing.entitlements.changed over Kafka) -> a delayed payment decided by the provider's own webhook -> cancel at
  period end -> webhook signature rules and replay through the gateway -> isolation.

Usage: p16_smoke.py http://localhost:8080 http://127.0.0.1:8025
       p16_smoke.py --account <gateway> <mailpit>   (prints one fresh signed-in account as JSON; trail-check uses it)
Env: BILLING_MOCK_WEBHOOK_SECRET (to sign crafted webhooks).
"""
import base64
import hashlib
import hmac
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(__file__))
ACCOUNT_ONLY = len(sys.argv) > 1 and sys.argv[1] == "--account"
if ACCOUNT_ONLY:
    sys.argv.pop(1)
if len(sys.argv) < 3:
    sys.argv.append("http://127.0.0.1:8025")
import p04_smoke as p4  # noqa: E402

GW = sys.argv[1].rstrip("/")
call, check, code, bearer = p4.call, p4.check, p4.code, p4.bearer
PASSWORD = "Smoke-Billing-Password-16"


def ip():
    return {"X-Forwarded-For": "198.51.100.%d" % random.randint(1, 254)}


def claims(jwt):
    part = jwt.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


def fresh_account(prefix):
    """Registers, verifies (Mailpit) and signs in a new account. Returns {email, access, refresh, account}."""
    email = f"{prefix}-{uuid.uuid4().hex[:10]}@smoke.test"
    s, _, d = call("POST", GW + "/v1/auth/register", {"email": email, "password": PASSWORD, "displayName": prefix},
                   dict(ip(), **{"Idempotency-Key": str(uuid.uuid4())}))
    if s != 202:
        raise SystemExit(f"register {email}: {s} {d}")
    tok = p4.mail_token(email, "Verify", "verify", seconds=30)
    if not tok:
        raise SystemExit(f"no verification mail for {email}")
    s, _, d = call("POST", GW + "/v1/auth/verify-email", {"token": tok}, ip())
    if s != 200:
        raise SystemExit(f"verify {email}: {s} {d}")
    s, _, t = call("POST", GW + "/v1/auth/login", {"email": email, "password": PASSWORD}, ip())
    if s != 200:
        raise SystemExit(f"login {email}: {s} {t}")
    return {"email": email, "access": t["accessToken"], "refresh": t["refreshToken"],
            "account": claims(t["accessToken"])["sub"]}


def checkout(tok, plan, pm, state, key=None):
    k = key or str(uuid.uuid4())
    s, h, d = call("POST", GW + "/v1/subscriptions/checkout",
                   {"plan": plan, "paymentMethod": pm, "billingState": state},
                   bearer(tok, **{"Idempotency-Key": k}))
    return s, h, d, k


def me(tok):
    s, _, d = call("GET", GW + "/v1/subscriptions/me", headers=bearer(tok))
    return s, d


def wait(predicate, seconds=40, step=0.5):
    end = time.time() + seconds
    while True:
        v = predicate()
        if v:
            return v
        if time.time() > end:
            return None
        time.sleep(step)


def sign(body, at=None):
    secret = os.environ.get("BILLING_MOCK_WEBHOOK_SECRET", "").encode()
    t = int(at if at is not None else time.time())
    mac = hmac.new(secret, f"{t}.".encode() + body, hashlib.sha256).hexdigest()
    return f"t={t},v1={mac}"


def webhook(body, signature=None, provider="mock"):
    headers = {"Content-Type": "application/json"}
    if signature:
        headers["Webhook-Signature"] = signature
    req = urllib.request.Request(GW + "/v1/webhooks/" + provider, data=body, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"null")
        except ValueError:
            return e.code, raw.decode(errors="replace")


def gst(price):
    return (price * 1800 + 5000) // 10000  # 18 %, HALF_UP to the paisa (the shared Gst.on)


def main():
    # ---------------------------------------------------------------- plans: public
    s, h, d = call("GET", GW + "/v1/plans")
    plans = (d or {}).get("data") or []
    check("plans: public, NOT EMPTY (3)", s == 200 and len(plans) == 3, (s, d))
    by = {p["code"]: p for p in plans}
    prem = by.get("PREMIUM", {})
    check("plans: PREMIUM total = price + 18 % GST",
          prem.get("pricePaise", 0) > 0 and prem.get("totalPaise") == prem["pricePaise"] + gst(prem["pricePaise"]),
          prem)
    s, _, d = call("GET", GW + "/v1/plans/vip")
    check("plans: one by code", s == 200 and d.get("code") == "VIP", (s, d))

    u1 = fresh_account("smokep16a")
    tok = u1["access"]
    check("fresh account: token tier FREE", claims(tok).get("tier") == "FREE", claims(tok))
    s, d = me(tok)
    check("subscriptions/me before any checkout: 404 SUBSCRIPTION_NOT_FOUND",
          s == 404 and code(d) == "SUBSCRIPTION_NOT_FOUND", (s, d))
    s, _, d = call("GET", GW + "/v1/entitlements/me", headers=bearer(tok))
    check("entitlements: FREE with a NON-EMPTY map", s == 200 and d.get("tier") == "FREE"
          and len(d.get("entitlements") or {}) > 0, (s, d))

    # ---------------------------------------------------------------- a decline, then a checkout
    s, _, d, _ = checkout(tok, "PREMIUM", "pm_mock_decline", "KA")
    check("decline: 402 PAYMENT_DECLINED, nothing started", s == 402 and (d or {}).get("code") == "PAYMENT_DECLINED"
          and d["subscription"]["status"] == "incomplete_expired", (s, d))
    s, _, d = call("POST", GW + "/v1/subscriptions/checkout",
                   {"plan": "PREMIUM", "paymentMethod": "pm_mock_success", "billingState": "KA"}, bearer(tok))
    check("checkout without Idempotency-Key: 400", s == 400 and code(d) == "IDEMPOTENCY_KEY_REQUIRED", (s, d))
    s, h, d, k = checkout(tok, "PREMIUM", "pm_mock_success", "KA")
    ok = check("checkout: 201 succeeded, subscription active", s == 201 and (d or {}).get("outcome") == "succeeded"
               and d["subscription"]["status"] == "active", (s, d))
    if not ok:
        return
    first_body = d
    pay1 = d["payment"]["id"]
    invoice1 = d["invoiceId"]
    check("checkout: charged price + GST, provider id recorded",
          d["payment"]["amountPaise"] == prem["totalPaise"] and d["payment"]["providerPaymentId"].startswith("pay_"),
          d["payment"])
    s, h, d, _ = checkout(tok, "PREMIUM", "pm_mock_success", "KA", key=k)
    check("checkout replay: same body + Idempotent-Replayed", s == 201 and h.get("idempotent-replayed") == "true"
          and d == first_body, (s, h.get("idempotent-replayed")))
    s, _, d, _ = checkout(tok, "VIP", "pm_mock_success", "KA", key=k)
    check("same key, another body: 422 IDEMPOTENCY_KEY_REUSED", s == 422 and code(d) == "IDEMPOTENCY_KEY_REUSED",
          (s, d))
    s, _, d, _ = checkout(tok, "VIP", "pm_mock_success", "KA")
    check("a second checkout while one is live: 409 SUBSCRIPTION_EXISTS",
          s == 409 and code(d) == "SUBSCRIPTION_EXISTS", (s, d))

    # ---------------------------------------------------------------- invoices: NOT EMPTY, then their arithmetic
    s, _, d = call("GET", GW + "/v1/invoices?limit=50", headers=bearer(tok))
    inv = (d or {}).get("data") or []
    if check("invoices: NOT EMPTY (the checkout's)", s == 200 and len(inv) >= 1, (s, d)):
        i = next((x for x in inv if x["id"] == invoice1), None)
        check("invoices: the checkout's invoice is listed", i is not None, [x["id"] for x in inv])
        if i:
            lines = i.get("lines") or []
            check("invoice: NON-EMPTY lines that add up to the total and the charge",
                  len(lines) >= 2 and sum(x["amountPaise"] for x in lines) == i["totalPaise"] == prem["totalPaise"],
                  i)
            check("invoice: Karnataka -> CGST + SGST (intra)", i["taxRegime"] == "intra"
                  and [x["kind"] for x in lines] == ["item", "cgst", "sgst"], i)
            check("invoice: numbered DX/<FY>/<serial>", i["number"].startswith("DX/"), i["number"])
    s, _, d = call("GET", GW + "/v1/invoices/" + invoice1, headers=bearer(tok))
    check("invoice by id", s == 200 and d.get("paymentId") == pay1, (s, d))

    # ---------------------------------------------------------------- entitlements, then the tier in a new token
    s, _, d = call("GET", GW + "/v1/entitlements/me", headers=bearer(tok))
    check("entitlements: PREMIUM, version 1, NON-EMPTY map with the plan's features",
          s == 200 and d.get("tier") == "PREMIUM" and d.get("version") == 1
          and d.get("entitlements", {}).get("walletOptimizer") is True, (s, d))
    state = {"refresh": u1["refresh"], "tier": None}

    def refreshed_tier():
        s2, _, t = call("POST", GW + "/v1/auth/token/refresh", {"refreshToken": state["refresh"]}, ip())
        if s2 != 200:
            return None
        state["refresh"] = t["refreshToken"]
        state["tier"] = claims(t["accessToken"]).get("tier")
        return state["tier"] == "PREMIUM"

    check("identity: a refreshed token carries tier PREMIUM (billing.entitlements.changed over Kafka)",
          wait(refreshed_tier, seconds=45, step=1.5), state["tier"])

    # ---------------------------------------------------------------- the provider's own webhooks, over real HTTP
    u2 = fresh_account("smokep16b")
    tok2 = u2["access"]
    s, _, d, _ = checkout(tok2, "VIP", "pm_mock_delayed", "MH")
    check("delayed payment: 202 pending, subscription incomplete", s == 202 and (d or {}).get("outcome") == "pending"
          and d["subscription"]["status"] == "incomplete", (s, d))
    pay2 = (d or {}).get("payment", {}).get("id")
    active = wait(lambda: (me(tok2)[1] or {}).get("status") == "active", seconds=40)
    check("delayed payment: the provider's webhook (HTTP, HMAC-signed) activated it", active, me(tok2))
    s, _, d = call("GET", GW + "/v1/invoices", headers=bearer(tok2))
    inv2 = (d or {}).get("data") or []
    if check("delayed payment: invoices NOT EMPTY", s == 200 and len(inv2) == 1, (s, d)):
        check("Maharashtra -> IGST (inter), same total formula", inv2[0]["taxRegime"] == "inter"
              and [x["kind"] for x in inv2[0]["lines"]] == ["item", "igst"]
              and inv2[0]["totalPaise"] == by["VIP"]["totalPaise"], inv2[0])

    # ---------------------------------------------------------------- cancel at period end
    s, _, d = call("POST", GW + "/v1/subscriptions/me:cancel", headers=bearer(tok))
    check("cancel: 200, cancelAtPeriodEnd, still active", s == 200 and d.get("cancelAtPeriodEnd") is True
          and d.get("status") == "active", (s, d))
    s, _, d = call("POST", GW + "/v1/subscriptions/me:cancel", headers=bearer(tok))
    check("cancel again: 200, unchanged", s == 200 and d.get("cancelAtPeriodEnd") is True, (s, d))
    s, _, d = call("GET", GW + "/v1/entitlements/me", headers=bearer(tok))
    check("entitlements after cancel: PREMIUM until the period ends", d.get("tier") == "PREMIUM", d)

    # ---------------------------------------------------------------- webhooks through the gateway
    s, d = webhook(b'{"id":"evt_x","type":"payment.succeeded","data":{}}')
    check("webhook unsigned: 400 WEBHOOK_SIGNATURE_INVALID", s == 400 and code(d) == "WEBHOOK_SIGNATURE_INVALID",
          (s, d))
    if os.environ.get("BILLING_MOCK_WEBHOOK_SECRET"):
        body = json.dumps({"id": "evt_" + uuid.uuid4().hex, "type": "payment.succeeded",
                           "data": {"reference": str(uuid.uuid4()), "status": "succeeded"}}).encode()
        s, d = webhook(body, sign(body, time.time() - 600))
        check("webhook signed 10 minutes ago: 400 (outside the window)", s == 400 and (d or {}).get("reason") == "STALE",
              (s, d))
        s, d = webhook(body, sign(body))
        check("webhook for no Dexter payment: 200 ignored", s == 200 and d.get("status") == "ignored", (s, d))
        if pay2:
            ev = json.dumps({"id": "evt_smoke_" + uuid.uuid4().hex, "type": "payment.succeeded",
                             "data": {"reference": pay2, "status": "succeeded"}}).encode()
            s, d = webhook(ev, sign(ev))
            check("webhook: a new event for a decided payment is processed and changes nothing",
                  s == 200 and d.get("status") == "processed" and d.get("outcome") == "no_change", (s, d))
            s, d = webhook(ev, sign(ev))
            check("webhook replay: duplicate", s == 200 and d.get("status") == "duplicate", (s, d))
        s, d = webhook(body, sign(body), provider="nope")
        check("webhook for an unknown provider: 404", s == 404 and code(d) == "PROVIDER_UNKNOWN", (s, d))
    else:
        check("BILLING_MOCK_WEBHOOK_SECRET is set (needed to sign crafted webhooks)", False)

    # ---------------------------------------------------------------- isolation
    s, _, d = call("GET", GW + "/v1/invoices/" + invoice1, headers=bearer(tok2))
    check("another account's invoice: 404", s == 404, (s, d))
    s, _, d = call("GET", GW + "/v1/subscriptions/me", headers={"X-Account-Id": u1["account"]} | bearer(tok2))
    check("a client X-Account-Id header is ignored", s == 200 and d.get("planCode") == "VIP", (s, d))
    s, _, d = call("GET", GW + "/v1/subscriptions/me")
    check("subscriptions without a token: 401", s == 401, (s, d))


if __name__ == "__main__":
    if ACCOUNT_ONLY:
        print(json.dumps(fresh_account("trailp16")))
        sys.exit(0)
    main()
    print(f"# P16 smoke: {'PASS' if not p4.FAILS else 'FAIL (' + str(len(p4.FAILS)) + ')'}")
    sys.exit(1 if p4.FAILS else 0)
