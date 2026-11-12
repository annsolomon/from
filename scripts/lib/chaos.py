#!/usr/bin/env python3
"""P19 step 5 chaos tests, against the running local stack (verify.sh full: full.chaos). Each experiment breaks one
thing, keeps using the system through it, and asserts recovery on data the experiment itself wrote:

  kafka-down       Kafka stopped 60 s. Expense writes still succeed (the outbox takes them); the ledger relay reports
                   them pending; after Kafka returns every one is published (topic end offsets grow by at least the
                   number written) and every consumer group of the topic catches up (lag 0).
  relay-failover   A second ledger instance joins; the relay leader (dexter_outbox_leader = 1) is killed -9; the
                   other instance takes the outbox lock and publishes what is written after the kill.
  postgres-restart Postgres restarted: every service is UP again and reads and writes through the gateway succeed.
  redis-down       Redis stopped: reads through the gateway still work (rate limiter fails open), sign-in answers
                   503 (fails closed); after Redis returns sign-in works again.

Prints PASS/FAIL lines; exit 1 on any failure.
"""
import json
import os
import pathlib
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parents[2]
GW = "http://127.0.0.1:8080"
LEDGER = "http://127.0.0.1:8082"
SPARE_PORT = 8092
COMPOSE = ["docker", "compose", "--env-file", str(ROOT / ".env"), "-f", str(ROOT / "infra/compose/docker-compose.yml")]
results = []


def check(name, ok, detail=""):
    results.append(bool(ok))
    print(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}", flush=True)
    return ok


def http(method, url, body=None, headers=None, timeout=10):
    req = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode())
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, json.loads(raw) if raw.startswith(("{", "[")) else raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        return e.code, json.loads(raw) if raw.startswith("{") else raw
    except Exception as e:  # noqa: BLE001 - connection refused / timeout = status 0
        return 0, str(e)


def login():
    s, d = http("POST", GW + "/v1/auth/login", {"email": "asha@demo.in", "password": os.environ["DEMO_PASSWORD_ASHA"]})
    return d.get("accessToken") if s == 200 and isinstance(d, dict) else None


def write_expense(base, token, note):
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    body = {"amountPaise": 1234, "occurredAt": now, "merchant": "Chaos Cafe", "categoryPath": "food.cafe",
            "paymentMethod": "upi", "note": note, "tags": ["chaos"]}
    t0 = time.time()
    s, d = http("POST", base + "/v1/expenses", body,
                {"Authorization": "Bearer " + token, "Idempotency-Key": str(uuid.uuid4())}, timeout=15)
    return s, time.time() - t0


def metric(base, name, labels):
    s, text = http("GET", base + "/actuator/prometheus", timeout=5)
    if s != 200:
        return None
    pat = re.compile(r"^" + re.escape(name) + r"\{([^}]*)\}\s+([0-9.eE+-]+)", re.M)
    for lab, val in pat.findall(text):
        if all(f'{k}="{v}"' in lab for k, v in labels.items()):
            return float(val)
    return None


def kafka(*args, timeout=60):
    out = subprocess.run(COMPOSE + ["exec", "-T", "kafka", *args], capture_output=True, text=True, timeout=timeout)
    return out.stdout


def topic_end(topic):
    out = kafka("/opt/kafka/bin/kafka-get-offsets.sh", "--bootstrap-server", "localhost:9092", "--topic", topic)
    return sum(int(l.rsplit(":", 1)[1]) for l in out.split() if l.count(":") == 2)


def group_lag(topic):
    out = kafka("/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "localhost:9092", "--describe",
                "--all-groups", timeout=90)
    lag, groups = 0, set()
    for line in out.splitlines():
        cols = line.split()
        if len(cols) >= 6 and cols[1] == topic:
            groups.add(cols[0])
            lag += int(cols[5]) if cols[5].isdigit() else 0
    return lag, sorted(groups)


def compose(*args, timeout=180):
    return subprocess.run(COMPOSE + list(args), capture_output=True, text=True, timeout=timeout).returncode


def until(seconds, fn, every=2):
    end = time.time() + seconds
    while True:
        v = fn()
        if v or time.time() > end:
            return v
        time.sleep(every)


def healthy(port):
    s, d = http("GET", f"http://127.0.0.1:{port}/actuator/health", timeout=3)
    return s == 200 and isinstance(d, dict) and d.get("status") == "UP"


# ------------------------------------------------------------------ experiments
def kafka_down(token, topic):
    before = topic_end(topic)
    compose("stop", "-t", "5", "kafka")
    t_stop = time.time()
    codes, slow = [], 0.0
    for i in range(20):
        s, dt = write_expense(GW, token, f"chaos kafka-down {i}")
        codes.append(s)
        slow = max(slow, dt)
    check("kafka-down: writes succeed while Kafka is down", codes.count(201) == 20,
          f"{codes.count(201)}/20 created, slowest {slow:.2f}s")
    pending = until(50, lambda: (metric(LEDGER, "dexter_outbox_pending", {"schema": "ledger"}) or 0) >= 20)
    check("kafka-down: the ledger relay reports them pending", pending,
          f"dexter_outbox_pending{{schema=ledger}} = {metric(LEDGER, 'dexter_outbox_pending', {'schema': 'ledger'})}")
    time.sleep(max(0, 60 - (time.time() - t_stop)))
    compose("start", "kafka")
    t_back = time.time()
    caught_up = until(120, lambda: topic_end(topic) >= before + 20
                      and (metric(LEDGER, "dexter_outbox_pending", {"schema": "ledger"}) or 0) == 0, every=3)
    check("kafka-down: the outbox catches up after Kafka returns", caught_up,
          f"{topic} end offsets {before} -> {topic_end(topic)} in {time.time() - t_back:.0f}s")
    lag = until(120, lambda: group_lag(topic)[0] == 0 and group_lag(topic)[1], every=5)
    lag_now, groups = group_lag(topic)
    check("kafka-down: every consumer group of the topic catches up", lag and groups,
          f"lag {lag_now} across {groups}")


def start_spare_ledger():
    venv = subprocess.run([sys.executable, str(ROOT / "scripts/lib/vault_tool.py"), "env", "ledger"],
                          capture_output=True).stdout.split(b"\0")
    args = [a.decode() for a in venv if a]
    env_cmd = ["env", *args, "PORT_LEDGER=" + str(SPARE_PORT), "SPRING_PROFILES_ACTIVE=dev",
               "DEXTER_PRIVACY_ENABLED=true", "DEXTER_TRACING_ENABLED=true"]
    log = open(ROOT / ".run/logs/ledger-spare.log", "w")
    p = subprocess.Popen(env_cmd + ["java", "-Xms64m", "-Xmx256m", "-XX:TieredStopAtLevel=1", "-jar",
                                    str(ROOT / "services/ledger/target/app.jar")],
                         stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    (ROOT / ".run/pids/ledger-spare.pid").write_text(str(p.pid))
    return p


def restart_main_ledger():
    venv = subprocess.run([sys.executable, str(ROOT / "scripts/lib/vault_tool.py"), "env", "ledger"],
                          capture_output=True).stdout.split(b"\0")
    args = [a.decode() for a in venv if a]
    log = open(ROOT / ".run/logs/ledger.log", "a")
    p = subprocess.Popen(["env", *args, "SPRING_PROFILES_ACTIVE=dev", "DEXTER_PRIVACY_ENABLED=true",
                          "DEXTER_TRACING_ENABLED=true", "java", "-Xms64m", "-Xmx256m", "-XX:TieredStopAtLevel=1",
                          "-jar", str(ROOT / "services/ledger/target/app.jar")],
                         stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    (ROOT / ".run/pids/ledger.pid").write_text(str(p.pid))
    return until(180, lambda: healthy(8082), every=3)


def relay_failover(token, topic):
    spare = start_spare_ledger()
    if not check("relay-failover: a second ledger instance starts", until(180, lambda: healthy(SPARE_PORT), every=3),
                 f"port {SPARE_PORT}"):
        return
    leaders = {}
    def who():
        leaders.clear()
        for port in (8082, SPARE_PORT):
            leaders[port] = metric(f"http://127.0.0.1:{port}", "dexter_outbox_leader", {"schema": "ledger"})
        return [p for p, v in leaders.items() if v == 1.0]
    lead = until(30, lambda: len(who()) == 1 and who())
    if not check("relay-failover: exactly one instance leads", lead, f"dexter_outbox_leader per port {leaders}"):
        spare.kill()
        return
    leader = lead[0]
    survivor = SPARE_PORT if leader == 8082 else 8082
    pid = int((ROOT / ".run/pids" / ("ledger.pid" if leader == 8082 else "ledger-spare.pid")).read_text())
    os.kill(pid, signal.SIGKILL)
    t_kill = time.time()
    took = until(60, lambda: metric(f"http://127.0.0.1:{survivor}", "dexter_outbox_leader", {"schema": "ledger"}) == 1.0,
                 every=1)
    check("relay-failover: the other instance takes the lock", took,
          f"leader :{leader} killed -9, :{survivor} leads after {time.time() - t_kill:.1f}s")
    before = topic_end(topic)
    codes = [write_expense(f"http://127.0.0.1:{survivor}", token, f"chaos failover {i}")[0] for i in range(10)]
    published = until(60, lambda: topic_end(topic) >= before + 10, every=2)
    check("relay-failover: writes after the kill are published by the new leader",
          codes.count(201) == 10 and published, f"{codes.count(201)}/10 created on :{survivor}; offsets {before} -> "
                                                f"{topic_end(topic)}")
    # back to one ledger on :8082 for the rest of the run
    if leader == 8082:
        check("relay-failover: ledger :8082 restarted", restart_main_ledger(), "")
    try:
        os.killpg(spare.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    (ROOT / ".run/pids/ledger-spare.pid").unlink(missing_ok=True)


def postgres_restart(token):
    t0 = time.time()
    compose("restart", "-t", "10", "postgres")
    ports = [8080 + i for i in range(10)]
    up = until(120, lambda: all(healthy(p) for p in ports), every=3)
    down = [p for p in ports if not healthy(p)]
    check("postgres-restart: every service is UP again", up, f"{time.time() - t0:.0f}s; down: {down or 'none'}")
    def works():
        s1, _ = http("GET", GW + "/v1/expenses?limit=5", headers={"Authorization": "Bearer " + token})
        s2, _ = write_expense(GW, token, "chaos after postgres restart")
        return s1 == 200 and s2 == 201 and (s1, s2)
    ok = until(60, works, every=3)
    check("postgres-restart: pools recovered (read 200, write 201 through the gateway)", ok,
          f"{time.time() - t0:.0f}s after the restart")


def redis_down(token):
    compose("stop", "-t", "5", "redis")
    s_read, _ = http("GET", GW + "/v1/expenses?limit=5", headers={"Authorization": "Bearer " + token})
    s_cat, _ = http("GET", GW + "/v1/categories", headers={"Authorization": "Bearer " + token})
    check("redis-down: reads through the gateway still work (limiter fails open)", s_read == 200 and s_cat == 200,
          f"GET /v1/expenses {s_read}, GET /v1/categories {s_cat}")
    s_login, d = http("POST", GW + "/v1/auth/login",
                      {"email": "asha@demo.in", "password": os.environ["DEMO_PASSWORD_ASHA"]})
    check("redis-down: sign-in fails closed (503)", s_login == 503,
          f"POST /v1/auth/login {s_login} {d.get('code') if isinstance(d, dict) else ''}")
    compose("start", "redis")
    back = until(60, lambda: login() is not None, every=3)
    check("redis-down: sign-in works again after Redis returns", back, "")


def main():
    for k in ("DEMO_PASSWORD_ASHA",):
        if not os.environ.get(k):
            sys.exit(f"{k} not set")
    token = login()
    if not check("setup: asha signs in", token):
        sys.exit(1)
    topics = kafka("/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server", "localhost:9092", "--list").split()
    topic = next((t for t in topics if re.fullmatch(r"dexter\.ledger\.expense\.v1", t)), None)
    if not check("setup: the ledger expense topic exists", topic, topic or str([t for t in topics if 'ledger' in t])):
        sys.exit(1)
    for name, fn in (("kafka-down", lambda: kafka_down(token, topic)),
                     ("relay-failover", lambda: relay_failover(token, topic)),
                     ("postgres-restart", lambda: postgres_restart(token)),
                     ("redis-down", lambda: redis_down(token))):
        t0 = time.time()
        try:
            fn()
        except Exception as e:  # noqa: BLE001 - an experiment that crashes is a failure, the next one still runs
            check(f"{name}: ran", False, repr(e))
        print(f"----  {name} took {time.time() - t0:.0f}s", flush=True)
    print(f"chaos: {sum(results)} PASS / {len(results) - sum(results)} FAIL")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
