#!/usr/bin/env python3
"""P02 API smoke against the dev-only lab (ledger /_dev/p02, dexter-lab): JWT, Idempotency-Key, outbox -> Kafka ->
projection + DLQ, cache (hit/miss, Path A, outage bypass, purge, tenant isolation). Prints PASS/FAIL lines; exit 1 on
any failure. Usage: p02_smoke.py http://localhost:8082/_dev/p02"""
import json
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

LAB = sys.argv[1].rstrip("/")
FAILS = []
ASHA = "0192a5a1-0000-7000-8000-000000000001"


def call(method, path, body=None, headers=None, timeout=40):
    data = json.dumps(body).encode() if body is not None else None
    h = {"Accept": "application/json", "X-Request-Id": "smoke-p02-" + uuid.uuid4().hex[:8]}
    if data is not None:
        h["Content-Type"] = "application/json"
    h.update(headers or {})
    req = urllib.request.Request(LAB + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status, hdrs, raw = r.status, r.headers, r.read().decode()
    except urllib.error.HTTPError as e:
        status, hdrs, raw = e.code, e.headers, e.read().decode()
    except Exception as e:  # noqa: BLE001 - report any transport error as a failed call
        return 0, {}, {"transport_error": str(e)}, ""
    hd = {k.lower(): v for k, v in hdrs.items()}
    try:
        parsed = json.loads(raw) if raw else None
    except ValueError:
        parsed = raw
    return status, hd, parsed, raw


def check(label, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + label + ("" if ok else "  " + str(detail)[:220]))
    if not ok:
        FAILS.append(label)
    return ok


def bearer(tok):
    return {"Authorization": "Bearer " + tok}


def token(persona="asha", defect="none"):
    s, _, d, _ = call("POST", "/jwt/token", {"persona": persona, "defect": defect})
    if s != 200:
        raise SystemExit(f"FAIL  mint token {persona}/{defect}: {s} {d}")
    return d["token"]


def wait(fn, seconds, step=0.5):
    end = time.time() + seconds
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(step)
    return fn()


def main():
    s, _, d, _ = call("GET", "/status?init=true", timeout=90)
    if not check("lab starts (Postgres lab db, Kafka topics, relay, consumers, Redis user lab)", s == 200 and d.get("ready") is True, d):
        print("# summary: FAIL (lab not ready; nothing else can run)")
        return 1

    # ---- JWT
    asha, ravi, svc = token("asha"), token("ravi"), token("svc-reco")
    s, _, d, _ = call("GET", "/jwt/whoami", headers=bearer(asha))
    check("valid user token accepted (kind USER, account from sub)", s == 200 and d["kind"] == "USER" and d["accountId"] == ASHA, d)
    s, _, d, _ = call("GET", "/jwt/whoami")
    check("no token -> 401 UNAUTHENTICATED", s == 401 and d.get("code") == "UNAUTHENTICATED", d)
    expected = {"expired": "EXPIRED", "not-yet-valid": "NOT_YET_VALID", "lifetime-too-long": "LIFETIME",
                "wrong-audience": "AUDIENCE", "wrong-issuer": "ISSUER", "wrong-typ": "TYPE",
                "hs256-confusion": "ALGORITHM", "unknown-kid": "KID_UNKNOWN", "embedded-jwk": "HEADER",
                "bad-signature": "SIGNATURE"}
    for defect, reason in expected.items():
        s, _, d, _ = call("GET", "/jwt/whoami", headers=bearer(token("asha", defect)))
        check(f"jwt {defect} -> 401 {reason}", s == 401 and d.get("reason") == reason, f"{s} {d}")
    s, _, d, _ = call("GET", "/jwt/whoami", headers=bearer(token("asha", "alg-none")))
    check("jwt alg none -> 401", s == 401 and d.get("reason") in ("MALFORMED", "ALGORITHM"), f"{s} {d}")
    s, _, d, _ = call("POST", "/notes", {"title": "svc", "amountPaise": 1}, {**bearer(svc), "Idempotency-Key": str(uuid.uuid4())})
    check("service token on a user endpoint -> 403 FORBIDDEN", s == 403 and d.get("code") == "FORBIDDEN", d)
    s, _, r, _ = call("POST", "/jwt/rotate")
    time.sleep(11)  # unknown-kid refetches are limited to one per 10 s
    s2, _, d, _ = call("GET", "/jwt/whoami", headers=bearer(token("asha")))
    s3, _, _, _ = call("GET", "/jwt/whoami", headers=bearer(asha))
    check("key rotation: new kid accepted via JWKS refetch, old-kid token still valid", s == 200 and s2 == 200 and s3 == 200, f"{s} {s2} {s3} {d}")
    asha = token("asha")

    # ---- Idempotency-Key
    run = uuid.uuid4().hex[:6]
    key = str(uuid.uuid4())
    body = {"title": "smoke-" + run, "amountPaise": 12345}
    s1, h1, d1, raw1 = call("POST", "/notes", body, {**bearer(asha), "Idempotency-Key": key})
    check("POST with key -> 201 + Location", s1 == 201 and "location" in h1, f"{s1} {d1}")
    s2, h2, _, raw2 = call("POST", "/notes", body, {**bearer(asha), "Idempotency-Key": key})
    check("same key again -> byte-identical replay + Idempotent-Replayed", s2 == 201 and raw2 == raw1
          and h2.get("idempotent-replayed") == "true" and h2.get("location") == h1.get("location"), f"{s2} {h2}")
    s, _, d, _ = call("POST", "/notes", {**body, "amountPaise": 1}, {**bearer(asha), "Idempotency-Key": key})
    check("same key, different body -> 422 IDEMPOTENCY_KEY_REUSED", s == 422 and d.get("code") == "IDEMPOTENCY_KEY_REUSED", d)
    s, _, d, _ = call("POST", "/notes", body, bearer(asha))
    check("no key -> 400 IDEMPOTENCY_KEY_REQUIRED", s == 400 and d.get("code") == "IDEMPOTENCY_KEY_REQUIRED", d)
    s, _, d, _ = call("POST", "/notes", body, {"Idempotency-Key": str(uuid.uuid4())})
    check("no token -> 401", s == 401, d)
    k2, held = str(uuid.uuid4()), {}
    t = threading.Thread(target=lambda: held.update(r=call("POST", "/notes?holdMs=3500", {**body, "title": "held-" + run},
                                                          {**bearer(asha), "Idempotency-Key": k2})))
    t.start()
    time.sleep(0.6)
    s, h, d, _ = call("POST", "/notes", {**body, "title": "held-" + run}, {**bearer(asha), "Idempotency-Key": k2})
    t.join()
    check("same key while the first holds it -> 409 IDEMPOTENCY_IN_PROGRESS + Retry-After",
          s == 409 and d.get("code") == "IDEMPOTENCY_IN_PROGRESS" and h.get("retry-after") == "1", f"{s} {h.get('retry-after')} {d}")
    check("the held request completes 201", held.get("r", (0,))[0] == 201, held.get("r"))

    # ---- outbox -> relay -> Kafka -> projection
    note = d1
    nid = note["id"]
    def projected():
        d = call("GET", "/projection", headers=bearer(asha))[2]
        return isinstance(d, dict) and any(x["title"] == body["title"] for x in d.get("titles", []))
    proj = wait(projected, 30)
    check("outbox -> relay -> Kafka -> projector (inbox + version guard)", proj, "title not projected within 30 s")
    rows = call("GET", "/outbox", headers=bearer(asha))[2]
    check("outbox rows marked published", isinstance(rows, list) and rows and all(r["publishedAt"] for r in rows[:3]), rows)
    s, _, d, _ = call("POST", "/notes", {"title": "always-fails", "amountPaise": 1}, {**bearer(asha), "Idempotency-Key": str(uuid.uuid4())})
    def in_dlq():
        d = call("GET", "/dlq", headers=bearer(asha))[2]
        return isinstance(d, list) and any(x.get("type") == "lab.note.created" and x.get("error") for x in d) and d
    dl = wait(in_dlq, 40, 1.0)
    check("poison event: retry-2s -> retry-5s -> DLQ with attempt/error headers", bool(dl), dl)

    # ---- cache
    def read(tok=asha, i=nid):
        s, h, d, _ = call("GET", f"/notes/{i}", headers=bearer(tok))
        return s, h.get("x-dexter-cache"), d
    s, how1, _ = read()
    hit = wait(lambda: read()[1] == "hit", 5, 0.2)  # a late Path B bump may cost one more miss
    check("cached read: miss, then hit", s == 200 and how1 in ("miss", "hit") and hit, f"{s} {how1} {hit}")
    s, _, d, _ = call("PUT", f"/notes/{nid}", {"title": "renamed-" + run, "version": note["version"]}, bearer(asha))
    check("rename (versioned) -> 200", s == 200 and d.get("version") == note["version"] + 1, d)
    s, how, d = read()
    check("after the write: miss with the new value (Path A after commit)", s == 200 and how == "miss" and d["title"] == "renamed-" + run, f"{how} {d}")
    s, _, d, _ = call("PUT", f"/notes/{nid}", {"title": "stale", "version": note["version"]}, bearer(asha))
    check("stale version -> 412 VERSION_CONFLICT", s == 412 and d.get("code") == "VERSION_CONFLICT", d)
    s, how, d = read(ravi)
    check("another tenant reading the id -> 404 (RLS + per-account keys)", s == 404, f"{s} {d}")
    s, _, d, _ = call("GET", "/notes?limit=2", headers=bearer(asha))
    check("cached keyset list", s == 200 and len(d["data"]) <= 2 and "page" in d, d)
    call("POST", "/cache/redis/cut")
    try:
        s, how, d = read()
        check("Redis down -> read bypasses to the DB (200, correct value)", s == 200 and how == "bypass" and d["title"] == "renamed-" + run, f"{s} {how}")
    finally:
        call("POST", "/cache/redis/restore")
    back = wait(lambda: read()[1] == "hit", 15, 0.5)
    check("Redis back -> hits again", back, "still not hitting after 15 s")
    s, _, d, _ = call("GET", "/cache", headers=bearer(asha))
    check("cache keys of the account visible", s == 200 and any(ASHA in k for k in d.get("keys", [])), d)
    s, _, d, _ = call("POST", "/cache/purge", headers=bearer(asha))
    # a Path B retry landing right after the purge may legitimately re-create the generation counter; no value or lock
    # key (cached data) may remain
    check("privacy purge removes every cached entry of the account", s == 200 and d.get("removed", 0) > 0
          and not [k for k in d.get("keys", []) if not k.startswith("gen:")], d)

    print("# P02 summary: " + ("PASS" if not FAILS else "FAIL (" + ", ".join(FAILS) + ")"))
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
