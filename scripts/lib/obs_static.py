#!/usr/bin/env python3
"""P19 static check of the observability config (verify.sh static.obs-config; no Docker needed):

  - every Grafana dashboard is valid JSON with a unique uid, and every panel has at least one non-empty query
  - every metric a dashboard or alert queries is one the services export (KNOWN) or a recording rule defined in
    alerts.yml - a typo in a metric name gives an empty panel / an alert that never fires, which no test would see
  - every alert names a runbook, and that runbook file exists
  - the provisioning file points at the folder the compose file mounts the dashboards into
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
KNOWN = {
    "up",
    "http_server_requests_seconds_count", "http_server_requests_seconds_bucket",
    "dexter_outbox_lag_millis", "dexter_outbox_pending", "dexter_dlq_messages",
    "dexter_event_handle_seconds_count", "dexter_event_handle_seconds_bucket",
    "kafka_consumer_fetch_manager_records_lag_max",
    "hikaricp_connections_active", "hikaricp_connections_pending", "hikaricp_connections_max",
    "hikaricp_connections_acquire_seconds_max",
    "jvm_memory_used_bytes", "jvm_memory_max_bytes", "jvm_gc_pause_seconds_sum",
    "dexter_sse_connections",
}
FUNCS = {"sum", "max", "min", "avg", "count", "rate", "irate", "increase", "histogram_quantile", "topk", "bottomk",
         "clamp_min", "clamp_max", "label_values", "by", "without", "on", "ignoring", "and", "or", "unless"}
errors = []


def metrics_in(expr):
    expr = re.sub(r'"[^"]*"', '""', expr)  # label values are not metric names
    names = set(re.findall(r"([a-zA-Z_:][a-zA-Z0-9_:]*)\s*\{", expr))
    names |= {m for m in re.findall(r"(?<![\w{=~\"])([a-z_]+:[a-z0-9_:]+)", expr)}
    return {n for n in names if n not in FUNCS}


alerts_text = (ROOT / "infra/prometheus/alerts.yml").read_text()
recorded = set(re.findall(r"^\s*- record:\s*(\S+)", alerts_text, re.M))
known = KNOWN | recorded

# ---- dashboards
uids, panels, queries = set(), 0, 0
for f in sorted((ROOT / "infra/grafana/dashboards").glob("*.json")):
    try:
        d = json.loads(f.read_text())
    except json.JSONDecodeError as e:
        errors.append(f"{f.name}: invalid JSON ({e})")
        continue
    if d.get("uid") in uids or not d.get("uid"):
        errors.append(f"{f.name}: missing or duplicate uid {d.get('uid')!r}")
    uids.add(d.get("uid"))
    for p in d.get("panels", []):
        panels += 1
        exprs = [t.get("expr", "") for t in p.get("targets", []) if t.get("expr", "").strip()]
        if not exprs:
            errors.append(f"{f.name}: panel {p.get('title')!r} has no query")
        for e in exprs:
            queries += 1
            for m in metrics_in(e) - known:
                errors.append(f"{f.name}: panel {p.get('title')!r} queries unknown metric {m}")
if len(uids) < 3:
    errors.append(f"expected >= 3 dashboards, found {len(uids)}")

# ---- alerts: expressions span "expr:" lines and their ">-" continuation lines
alerts = re.findall(r"^\s*- alert:\s*(\S+)", alerts_text, re.M)
blocks = re.split(r"^\s*- (?=alert:|record:)", alerts_text, flags=re.M)[1:]
for b in blocks:
    m = re.search(r"expr:\s*(>-\s*\n(?:\s{10,}.*\n?)+|.*)", b)
    expr = (m.group(1) if m else "").replace(">-", " ")
    name = b.split("\n", 1)[0]
    if not expr.strip():
        errors.append(f"alerts.yml: {name} has no expr")
    for x in metrics_in(expr) - known:
        errors.append(f"alerts.yml: {name} queries unknown metric {x}")
    if name.startswith("alert:"):
        rb = re.search(r"runbook:\s*(\S+)", b)
        if not rb:
            errors.append(f"alerts.yml: {name} has no runbook annotation")
        elif not (ROOT / rb.group(1).split("#")[0]).is_file():
            errors.append(f"alerts.yml: {name} runbook {rb.group(1)} does not exist")
if len(alerts) < 8:
    errors.append(f"expected >= 8 alerts, found {len(alerts)}")

# ---- provisioning ↔ compose mount
prov = (ROOT / "infra/grafana/provisioning/dashboards/dashboards.yml").read_text()
path = re.search(r"path:\s*(\S+)", prov).group(1)
if f"../grafana/dashboards:{path}" not in (ROOT / "infra/compose/docker-compose.yml").read_text():
    errors.append(f"compose does not mount infra/grafana/dashboards at {path}")

for e in errors:
    print("ERROR", e)
print(f"{len(uids)} dashboards, {panels} panels, {queries} queries; {len(alerts)} alerts, {len(recorded)} recording "
      f"rules; {len(errors)} errors")
sys.exit(1 if errors else 0)
