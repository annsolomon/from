#!/usr/bin/env python3
"""P19 (ADR-0025) live observability check, run by verify.sh full after the API smoke and k6 smoke have driven
traffic through the stack. Every check reads data the run itself produced and asserts it is NOT EMPTY:

  prometheus  all 10 service targets up; the alert/recording rules loaded and healthy; the request histograms,
              outbox-lag, DLQ, handler, Kafka-client, Hikari and SSE series present for the services that own them
  tempo       every service exported spans; a trace that starts at the gateway contains a consumer span from
              another service (the traceparent crossed the outbox and Kafka) and spans from >= 3 services
  grafana     the three provisioned Dexter dashboards are loaded

Prints one PASS/FAIL line per check, writes them to $OBS_OUT, exits 1 on any FAIL.
"""
import base64
import json
import os
import sys
import time
import urllib.parse
import urllib.request

PROM = os.environ.get("PROM_URL", "http://127.0.0.1:9090")
TEMPO = os.environ.get("TEMPO_URL", "http://127.0.0.1:3200")
GRAFANA = os.environ.get("GRAFANA_URL", "http://127.0.0.1:3000")
SERVICES = ["gateway", "identity", "ledger", "catalog", "portfolio", "planning", "reco", "billing", "commerce",
            "audit"]
DASHBOARDS = {"dexter-red", "dexter-events", "dexter-resources"}
results = []


def get(url, auth=None, timeout=10):
    req = urllib.request.Request(url)
    if auth:
        req.add_header("Authorization", "Basic " + base64.b64encode(auth.encode()).decode())
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def check(name, ok, detail):
    results.append(("PASS" if ok else "FAIL", name, detail))
    print(f"{'PASS' if ok else 'FAIL'}  {name}: {detail}", flush=True)


def until(deadline_s, fn):
    """Retries fn (returning (ok, detail)) until it passes or the deadline passes; returns the last result."""
    end = time.time() + deadline_s
    while True:
        try:
            ok, detail = fn()
        except Exception as e:  # noqa: BLE001 - a probe error is a retryable miss
            ok, detail = False, f"error: {e}"
        if ok or time.time() > end:
            return ok, detail
        time.sleep(5)


def prom(q):
    return get(f"{PROM}/api/v1/query?" + urllib.parse.urlencode({"query": q}))["data"]["result"]


def services_of(q):
    return sorted({r["metric"].get("service", "?") for r in prom(q)})


# ------------------------------------------------------------------ prometheus
def targets():
    t = get(f"{PROM}/api/v1/targets?state=active")["data"]["activeTargets"]
    mine = [x for x in t if x["labels"].get("job") == "dexter-services"]
    up = [x for x in mine if x["health"] == "up"]
    return len(mine) == 10 and len(up) == 10, f"{len(up)}/{len(mine)} dexter-services targets up"


check("prometheus.targets", *until(90, targets))


def rules():
    groups = get(f"{PROM}/api/v1/rules")["data"]["groups"]
    rs = [r for g in groups for r in g["rules"]]
    bad = [r["name"] for r in rs if r.get("health") != "ok"]
    alerts = [r for r in rs if r["type"] == "alerting"]
    return (len(groups) >= 4 and len(alerts) >= 8 and not bad,
            f"{len(groups)} groups, {len(alerts)} alerts, {len(rs) - len(alerts)} recording; unhealthy: {bad or 'none'}")


check("prometheus.rules", *until(60, rules))


def series(name, q, want_services, minimum=None):
    def probe():
        got = services_of(q)
        missing = [s for s in want_services if s not in got]
        n = len(prom(q))
        ok = not missing and n > 0 and (minimum is None or n >= minimum)
        return ok, f"{n} series from {len(got)} services ({', '.join(got)}); missing: {missing or 'none'}"
    check(name, *until(60, probe))


series("metrics.http-histogram", 'sum by (service) (http_server_requests_seconds_bucket{application="dexter"})',
       SERVICES)
# The relay leader of every service with an outbox reports its lag (the gateway has none).
series("metrics.outbox-lag", 'max by (service, schema) (dexter_outbox_lag_millis{application="dexter"})',
       [s for s in SERVICES if s != "gateway"])
# Every service that consumes (its own projections, or the privacy saga - P18) measures its DLQs; catalog and the
# gateway consume nothing.
CONSUMING = [s for s in SERVICES if s not in ("gateway", "catalog")]
series("metrics.dlq-gauge", 'max by (service, consumer) (dexter_dlq_messages{application="dexter"})', CONSUMING)
series("metrics.kafka-client", 'max by (service) (kafka_consumer_fetch_manager_records_lag_max{application="dexter"})',
       CONSUMING)
series("metrics.hikari", 'max by (service) (hikaricp_connections_max{application="dexter"})',
       [s for s in SERVICES if s != "gateway"])
series("metrics.sse-gauge", 'max by (service) (dexter_sse_connections{application="dexter"})', ["reco"])


def handled():
    r = prom('sum by (service) (dexter_event_handle_seconds_count{application="dexter", outcome="ok"})')
    total = sum(float(x["value"][1]) for x in r)
    return total > 0 and len(r) >= 3, f"{int(total)} events handled ok across {len(r)} services"


check("metrics.events-handled", *until(60, handled))


def recording():
    r = prom("dexter:http_requests:rate5m")
    return len(r) >= 5, f"dexter:http_requests:rate5m has {len(r)} series"


check("prometheus.recording-rule", *until(90, recording))


# ------------------------------------------------------------------ tempo
def tempo_services():
    try:
        v = get(f"{TEMPO}/api/v2/search/tag/resource.service.name/values")
        names = {x.get("value") for x in v.get("tagValues", [])}
    except Exception:  # noqa: BLE001 - older API shape
        names = set(get(f"{TEMPO}/api/search/tag/service.name/values").get("tagValues", []))
    missing = [s for s in SERVICES if s not in names]
    return not missing, f"{len(names & set(SERVICES))}/10 services exported spans; missing: {missing or 'none'}"


check("tempo.services", *until(120, tempo_services))


def spans_of(trace):
    """(service, span name, kind) for every span; handles the v1 (batches) and v2 (trace.resourceSpans) shapes."""
    rs = trace.get("batches") or trace.get("resourceSpans") or trace.get("trace", {}).get("resourceSpans") or []
    out = []
    for b in rs:
        svc = next((a["value"].get("stringValue") for a in b.get("resource", {}).get("attributes", [])
                    if a["key"] == "service.name"), "?")
        for ss in b.get("scopeSpans", b.get("instrumentationLibrarySpans", [])):
            for sp in ss.get("spans", []):
                out.append((svc, sp.get("name", ""), str(sp.get("kind", ""))))
    return out


def crossing_trace():
    now = int(time.time())
    q = '{ resource.service.name = "gateway" } && { kind = consumer }'
    found = get(f"{TEMPO}/api/search?" + urllib.parse.urlencode(
        {"q": q, "start": now - 3600, "end": now, "limit": 50}), timeout=20).get("traces", [])
    best = (0, None, None)
    for t in found[:20]:
        spans = spans_of(get(f"{TEMPO}/api/traces/{t['traceID']}", timeout=20))
        svcs = {s for s, _, _ in spans}
        consumers = {s for s, n, k in spans if n.startswith("consume ") and ("CONSUMER" in k.upper() or k == "5")}
        if consumers and len(svcs) > best[0]:
            best = (len(svcs), t["traceID"], (sorted(svcs), sorted(consumers)))
    if best[1] is None:
        return False, f"{len(found)} gateway traces with a consumer span; none had a 'consume <type>' span"
    svcs, consumers = best[2]
    return (best[0] >= 3,
            f"trace {best[1]}: {best[0]} services {svcs}; consumer spans in {consumers} "
            f"({len(found)} gateway→consumer traces found)")


check("tempo.trace-across-kafka", *until(120, crossing_trace))


# ------------------------------------------------------------------ grafana
def dashboards():
    auth = f"{os.environ.get('GRAFANA_ADMIN_USER', 'admin')}:{os.environ.get('GRAFANA_ADMIN_PASSWORD', '')}"
    found = {d["uid"] for d in get(f"{GRAFANA}/api/search?type=dash-db&tag=dexter", auth=auth)}
    ds = get(f"{GRAFANA}/api/datasources", auth=auth)
    return (DASHBOARDS <= found and len(ds) >= 2,
            f"dashboards {sorted(found)}; datasources {sorted(d['uid'] for d in ds)}")


check("grafana.dashboards", *until(60, dashboards))

out = os.environ.get("OBS_OUT")
if out:
    with open(out, "w") as f:
        for status, name, detail in results:
            f.write(f"{status}\t{name}\t{detail}\n")
fails = [r for r in results if r[0] == "FAIL"]
print(f"obs check: {len(results) - len(fails)} PASS / {len(fails)} FAIL")
sys.exit(1 if fails else 0)
