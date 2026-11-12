#!/usr/bin/env python3
"""P19 (ADR-0026): Vault for the local stack — KV for each service's secrets, Transit for the identity KEK.

  vault_tool.py init        transit engine + key dexter-kek; secret/dexter/<svc> = the secrets that service's
                            application.yml references (values from the environment, i.e. .env); one policy and one
                            token per service (.run/vault/<svc>.token, mode 600)
  vault_tool.py env <svc>   NUL-separated arguments for env(1): -u for every secret of every service, then the
                            service's own secrets read from Vault WITH ITS OWN TOKEN (identity also gets VAULT_ADDR,
                            VAULT_TOKEN and the Transit KEK as active). scripts/run.sh starts each service through it,
                            so a process holds only what its policy lets it read - the Vault Agent pattern.
  vault_tool.py check       verify.sh full: policies isolate services, every running service's environment holds its
                            own secrets and nobody else's, and identity's wrapped keys are all under Transit.
  vault_tool.py secrets <svc>   the secret names a service references (for the runbook / debugging)

Locally Vault is a single server with file storage (a compose volume); init writes its one unseal key and the root
token to .run/vault/init.json (dev only - production: Raft cluster, auto-unseal, Kubernetes auth and the Agent
injector, docs/runbooks/deploy.md; the services do not change).
"""
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
ADDR = os.environ.get("VAULT_ADDR_HOST") or f"http://127.0.0.1:{os.environ.get('VAULT_PORT', '8200')}"
TOKENS = ROOT / ".run" / "vault"
INIT = TOKENS / "init.json"
TRANSIT_KEY = "dexter-kek"
SERVICES = ["gateway", "identity", "ledger", "catalog", "portfolio", "planning", "reco", "billing", "commerce",
            "audit"]
SECRET = re.compile(r"(PW_|PASSWORD|SECRET|_KEY\b|_KEY_|PEPPER|SALT|KEK_K[0-9])")
PLACEHOLDER = re.compile(r"\$\{([A-Z][A-Z0-9_]*)")


def api(method, path, token, body=None, ok=(200, 204)):
    req = urllib.request.Request(f"{ADDR}/v1/{path}", method=method,
                                 data=None if body is None else json.dumps(body).encode())
    if token:
        req.add_header("X-Vault-Token", token)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        if e.code in ok:
            return e.code, {}
        return e.code, (json.loads(raw) if raw.startswith("{") else {"errors": [raw]})


def must(method, path, token, body=None):
    code, out = api(method, path, token, body)
    if code // 100 != 2:
        sys.exit(f"vault: {method} {path} -> {code} {out.get('errors')}")
    return out


def referenced(svc):
    """Secret names the service's configuration references (nested defaults included)."""
    names = set()
    for f in (ROOT / "services" / svc / "src/main/resources").glob("application*.yml"):
        names |= set(PLACEHOLDER.findall(f.read_text()))
    return sorted(n for n in names if SECRET.search(n))


def all_secret_names():
    """Every secret-looking variable in the environment (.env): what no service gets unless Vault gives it."""
    return sorted(n for n in os.environ if SECRET.search(n) and re.fullmatch(r"[A-Z][A-Z0-9_]*", n))


def policy(svc):
    rules = [f'path "secret/data/dexter/{svc}" {{ capabilities = ["read"] }}']
    if svc == "identity":
        rules += [f'path "transit/encrypt/{TRANSIT_KEY}" {{ capabilities = ["update"] }}',
                  f'path "transit/decrypt/{TRANSIT_KEY}" {{ capabilities = ["update"] }}']
    return "\n".join(rules)


def wait_ready(seconds=90):
    """Until the server answers at all (200 active, 501 not initialised, 503 sealed)."""
    end = time.time() + seconds
    while time.time() < end:
        try:
            with urllib.request.urlopen(f"{ADDR}/v1/sys/health", timeout=3):
                return
        except urllib.error.HTTPError:
            return
        except Exception:  # noqa: BLE001 - not up yet
            time.sleep(2)
    sys.exit(f"vault: not answering at {ADDR} after {seconds}s")


def unsealed_root():
    """Initialises (1 key share, dev) on first start, unseals, returns the root token."""
    wait_ready()
    TOKENS.mkdir(parents=True, exist_ok=True)
    os.chmod(TOKENS, 0o700)
    if not api("GET", "sys/init", "")[1].get("initialized"):
        out = must("PUT", "sys/init", "", {"secret_shares": 1, "secret_threshold": 1})
        INIT.write_text(json.dumps({"key": out["keys_base64"][0], "root_token": out["root_token"]}))
        os.chmod(INIT, 0o600)
        print("vault: initialised (unseal key + root token in .run/vault/init.json)")
    if not INIT.is_file():
        sys.exit("vault: initialised but .run/vault/init.json is gone - reset with: scripts/run.sh nuke")
    keys = json.loads(INIT.read_text())
    if api("GET", "sys/seal-status", "")[1].get("sealed", True):
        if must("PUT", "sys/unseal", "", {"key": keys["key"]}).get("sealed"):
            sys.exit("vault: still sealed after unseal")
    for _ in range(30):  # active node after unseal
        if api("GET", "sys/health", "")[0] == 200:
            break
        time.sleep(1)
    return keys["root_token"]


def init():
    root = unsealed_root()
    mounts = must("GET", "sys/mounts", root)
    mounted = mounts.get("data", mounts)
    if "secret/" not in mounted:
        must("POST", "sys/mounts/secret", root, {"type": "kv", "options": {"version": "2"}})
    if "transit/" not in mounted:
        must("POST", "sys/mounts/transit", root, {"type": "transit"})
    # creating an existing key changes nothing (its versions, and what they wrapped, survive restarts)
    must("POST", f"transit/keys/{TRANSIT_KEY}", root, {"type": "aes256-gcm96"})
    stored = 0
    for svc in SERVICES:
        data = {n: os.environ[n] for n in referenced(svc) if n in os.environ}
        for _ in range(10):  # a fresh KV v2 mount upgrades itself for a moment
            code, out = api("POST", f"secret/data/dexter/{svc}", root, {"data": data})
            if code // 100 == 2:
                break
            time.sleep(1)
        else:
            sys.exit(f"vault: writing secret/dexter/{svc} -> {code} {out.get('errors')}")
        stored += len(data)
        must("PUT", f"sys/policies/acl/dexter-{svc}", root, {"policy": policy(svc)})
        tok = must("POST", "auth/token/create", root,
                   {"policies": [f"dexter-{svc}"], "ttl": "768h", "renewable": True,
                    "display_name": f"dexter-{svc}", "meta": {"service": svc}})["auth"]["client_token"]
        f = TOKENS / f"{svc}.token"
        f.write_text(tok)
        os.chmod(f, 0o600)
    print(f"vault ready: transit key {TRANSIT_KEY}; {stored} secrets for {len(SERVICES)} services under "
          f"secret/dexter/<svc>; {len(SERVICES)} scoped tokens in .run/vault")


def env(svc):
    tok = (TOKENS / f"{svc}.token").read_text().strip()
    code, out = api("GET", f"secret/data/dexter/{svc}", tok)
    if code != 200:
        sys.exit(f"vault: {svc} could not read its secrets ({code} {out.get('errors')})")
    mine = out["data"]["data"]
    args = []
    for n in sorted(set(all_secret_names()) | {"VAULT_TOKEN"}):
        args += ["-u", n]
    for k, v in sorted(mine.items()):
        args.append(f"{k}={v}")
    if svc == "identity":
        args += [f"VAULT_ADDR={ADDR}", f"VAULT_TOKEN={tok}", f"IDENTITY_KEK_ACTIVE=transit:{TRANSIT_KEY}"]
    sys.stdout.write("\0".join(args) + "\0")


# ------------------------------------------------------------------ check
results = []


def check(name, ok, detail):
    results.append(ok)
    print(f"{'PASS' if ok else 'FAIL'}  vault.{name}: {detail}", flush=True)


def psql(db, q):
    cmd = ["docker", "compose", "--env-file", str(ROOT / ".env"), "-f", str(ROOT / "infra/compose/docker-compose.yml"),
           "exec", "-T", "postgres", "psql", "-U", os.environ.get("POSTGRES_USER", "postgres"), "-d", db, "-tA",
           "-F", "|", "-c", q]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=60).stdout.strip()


def environ_of(svc):
    pid = (ROOT / ".run/pids" / f"{svc}.pid").read_text().strip()
    raw = pathlib.Path(f"/proc/{pid}/environ").read_bytes().split(b"\0")
    return {e.split(b"=", 1)[0].decode(): e.split(b"=", 1)[1].decode() for e in raw if b"=" in e}


def do_check():
    tok = {s: (TOKENS / f"{s}.token").read_text().strip() for s in SERVICES}
    # 1. isolation by policy
    own = {s: api("GET", f"secret/data/dexter/{s}", tok[s])[0] for s in SERVICES}
    check("own-secrets-readable", all(c == 200 for c in own.values()), f"{sum(c == 200 for c in own.values())}/10")
    cross = [(a, b, api("GET", f"secret/data/dexter/{b}", tok[a])[0])
             for a, b in [("ledger", "identity"), ("identity", "ledger"), ("gateway", "billing"), ("audit", "commerce")]]
    check("cross-service-denied", all(c == 403 for _, _, c in cross), ", ".join(f"{a}->{b}:{c}" for a, b, c in cross))
    enc = api("POST", f"transit/encrypt/{TRANSIT_KEY}", tok["ledger"], {"plaintext": "AA=="})[0]
    enc_id = api("POST", f"transit/encrypt/{TRANSIT_KEY}", tok["identity"], {"plaintext": "AA=="})[0]
    check("transit-only-identity", enc == 403 and enc_id == 200, f"ledger encrypt {enc}, identity encrypt {enc_id}")
    # 2. each running process holds its own secrets and nobody else's
    every = set(all_secret_names())
    bad, counted = [], 0
    for s in SERVICES:
        e = environ_of(s)
        mine = set(referenced(s)) & every
        held = {n for n in e if n in every}
        foreign = sorted(held - mine)
        missing = sorted(n for n in mine if n not in e)
        counted += len(held & mine)
        if foreign or missing:
            bad.append(f"{s}: foreign {foreign} missing {missing}")
        if s != "identity" and "VAULT_TOKEN" in e:
            bad.append(f"{s}: holds a Vault token")
    ident = environ_of("identity")
    if ident.get("IDENTITY_KEK_ACTIVE") != f"transit:{TRANSIT_KEY}" or not ident.get("VAULT_TOKEN"):
        bad.append("identity: not started with the Transit KEK active")
    check("process-environments", not bad and counted > 0,
          f"{counted} secrets across 10 processes, each its own only" if not bad else "; ".join(bad))
    # 3. identity's wrapped keys: new ones under Transit, older (seeded, k1) re-wrapped by the job
    end = time.time() + 150
    while True:
        usage = [l.split("|") for l in psql("identity", "SELECT kind, kek_id, n FROM identity.kek_usage()").splitlines()
                 if l]
        kinds = {k for k, kek, n in usage if kek == f"transit:{TRANSIT_KEY}" and int(n) > 0}
        stale = [(k, kek, n) for k, kek, n in usage if kek != f"transit:{TRANSIT_KEY}"]
        if (not stale and {"account_key", "signing_key"} <= kinds) or time.time() > end:
            break
        time.sleep(5)
    check("keys-under-transit", not stale and {"account_key", "signing_key"} <= kinds,
          f"usage {usage}" + (f"; still under another KEK: {stale}" if stale else ""))
    prefix = psql("identity", "SELECT count(*) FILTER (WHERE substring(dek_enc FROM 1 FOR 8) = 'vault:v1'::bytea),"
                  " count(*) FROM identity.account_keys WHERE destroyed_at IS NULL")
    v, n = (int(x) for x in (prefix if "|" in prefix else "0|0").split("|"))
    check("ciphertext-is-vaults", n > 0 and v == n, f"{v}/{n} account DEKs are Vault Transit ciphertext")
    print(f"vault check: {sum(results)} PASS / {len(results) - sum(results)} FAIL")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "init":
        init()
    elif cmd == "env" and len(sys.argv) == 3:
        env(sys.argv[2])
    elif cmd == "check":
        do_check()
    elif cmd == "secrets" and len(sys.argv) == 3:
        print("\n".join(referenced(sys.argv[2])))
    else:
        sys.exit(__doc__)
