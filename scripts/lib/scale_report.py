#!/usr/bin/env python3
"""P19 scale proof report: the MASTER_PLAN 8.1 rows, measured. Reads the k6 summary (end-to-end through the gateway)
and asks Prometheus (server side: per-service p99, ledger req/s, event lag) and pg_stat_statements (DB time of the
ledger's list query). Writes <dir>/results.md and prints it.

Usage: scale_report.py target/scale [sse-minutes]
"""
import json
import os
import pathlib
import subprocess
import sys
import urllib.parse
import urllib.request

OUT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "target/scale")
SSE_MIN = sys.argv[2] if len(sys.argv) > 2 else "?"
ROOT = pathlib.Path(__file__).resolve().parents[2]
PROM = os.environ.get("PROM_URL", "http://127.0.0.1:9090")
OUT.mkdir(parents=True, exist_ok=True)


def prom(q):
    try:
        url = f"{PROM}/api/v1/query?" + urllib.parse.urlencode({"query": q})
        with urllib.request.urlopen(url, timeout=20) as r:
            return json.load(r)["data"]["result"]
    except Exception as e:  # noqa: BLE001
        return [{"metric": {"error": str(e)}, "value": [0, "nan"]}]


def one(q, scale=1.0):
    r = prom(q)
    try:
        return float(r[0]["value"][1]) * scale if r else None
    except (KeyError, ValueError, IndexError):
        return None


def psql(db, q):
    cmd = ["docker", "compose", "--env-file", str(ROOT / ".env"), "-f", str(ROOT / "infra/compose/docker-compose.yml"),
           "exec", "-T", "postgres", "psql", "-U", os.environ.get("POSTGRES_USER", "postgres"), "-d", db, "-tA",
           "-F", "\t", "-c", q]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=60).stdout.strip()


def fmt(v, unit="ms", nd=1):
    return "n/a" if v is None else f"{v:.{nd}f} {unit}".strip()


k6 = {}
if (OUT / "k6-summary.json").exists():
    k6 = json.loads((OUT / "k6-summary.json").read_text()).get("metrics", {})


def k6p(metric, stat="p(99)"):
    m = k6.get(metric) or {}
    v = m.get("values", m)  # summary-export puts the stats at the top level
    return v.get(stat)


RUN = "[60m]"  # the k6 run is ~20 min + the SSE soak; a 60 min window covers it from the report's end
rows = []


def row(metric, target, measured, met, how):
    rows.append((metric, target, measured, "met" if met else ("**not met**" if met is not None else "n/a"), how))


lp = k6p("http_req_duration{class:list}")
row("List p99, end-to-end (50/25/24 window)", "< 150 ms", fmt(lp), None if lp is None else lp < 150,
    "k6 `http_req_duration{class:list}` p99")
db = psql("ledger", "SELECT round(max(mean_exec_time)::numeric, 2), round(max(max_exec_time)::numeric, 1),"
          " sum(calls) FROM pg_stat_statements WHERE query ILIKE '%FROM ledger.expenses%' AND query ILIKE '%LIMIT%'"
          " AND calls > 100")
db_mean = float(db.split("\t")[0]) if db and db.split("\t")[0] not in ("", None) else None
row("List DB time (mean of the keyset query)", "< 10 ms", fmt(db_mean) + (f" (max {db.split(chr(9))[1]} ms, "
    f"{db.split(chr(9))[2]} calls)" if db_mean is not None else ""), None if db_mean is None else db_mean < 10,
    "pg_stat_statements, ledger DB")
wp = k6p("http_req_duration{class:write}")
row("Write p99 (idempotency + outbox)", "< 200 ms", fmt(wp), None if wp is None else wp < 200,
    "k6 `http_req_duration{class:write}` p99")
rp = k6p("http_req_duration{class:reco}")
row("Recommendation (cached) p99", "< 100 ms", fmt(rp), None if rp is None else rp < 100,
    "k6 `http_req_duration{class:reco}` p99")
recompute = one('histogram_quantile(0.95, sum by (le) (rate(dexter_event_handle_seconds_bucket{service="reco",'
                f'outcome="ok"}}{RUN})))', 1000)
row("Recompute after the triggering event p95", "< 2 s", fmt(recompute), None if recompute is None else
    recompute < 2000, "reco handler p95 (`dexter_event_handle_seconds`), + outbox lag below")
rps = one(f'max_over_time(sum(rate(http_server_requests_seconds_count{{service="ledger",uri!~"/actuator.*"}}'
          f'[1m]))[60m:30s])')
row("Ledger throughput, one instance", ">= 1,500 req/s mixed", fmt(rps, "req/s", 0), None if rps is None else
    rps >= 1500, "Prometheus: peak 1-minute rate of ledger's HTTP requests")
lag = one(f'quantile_over_time(0.95, max(dexter_outbox_lag_millis{{schema="ledger"}}){RUN})')
row("Event lag (outbox → published) p95", "< 1 s", fmt(lag), None if lag is None else lag < 1000,
    "Prometheus: p95 over the run of the ledger relay's oldest-unpublished age (sampled every 5 s)")
sse_open = one('sum(dexter_sse_connections)')
sse_peak = one('max_over_time(sum(dexter_sse_connections)[60m:15s])')
row(f"SSE soak ({SSE_MIN} min)", "streams held; back to 0 after", f"peak {fmt(sse_peak, '', 0)}, now "
    f"{fmt(sse_open, '', 0)}; {int(k6p('dexter_sse_streams_opened', 'count') or 0)} streams opened",
    None if sse_open is None else (sse_open == 0 and (sse_peak or 0) > 0), "dexter_sse_connections, k6 counter")
fail = k6p("http_req_failed", "rate")
reqs = k6p("http_reqs", "count")
rate = k6p("http_reqs", "rate")

per_service = prom(f'histogram_quantile(0.99, sum by (service, le) (rate(http_server_requests_seconds_bucket'
                   f'{{application="dexter",uri!~"/actuator.*"}}{RUN})))')
top_routes = prom(f'topk(10, histogram_quantile(0.99, sum by (service, method, uri, le) (rate('
                  f'http_server_requests_seconds_bucket{{application="dexter",uri!~"/actuator.*"}}{RUN}))))')
pool = prom(f'max by (service, pool) (max_over_time(hikaricp_connections_pending{{application="dexter"}}{RUN}))')
heavy = psql("ledger", "SELECT round(mean_exec_time::numeric, 2), calls, left(regexp_replace(query, '\\s+', ' ', 'g'),"
             " 140) FROM pg_stat_statements WHERE calls > 50 ORDER BY total_exec_time DESC LIMIT 8")

md = ["# P19 scale proof — results", "",
      f"k6: {int(reqs or 0):,} requests, {fmt(rate, 'req/s', 0)} overall, failed rate "
      f"{'n/a' if fail is None else f'{fail:.4%}'}.", "",
      "| 8.1 metric | Target | Measured | | How |", "|---|---|---|---|---|"]
md += [f"| {a} | {b} | {c} | {d} | {e} |" for a, b, c, d, e in rows]
md += ["", "## Server-side p99 per service (Prometheus, excl. actuator)", "", "| Service | p99 |", "|---|---|"]
md += [f"| {r['metric'].get('service', '?')} | {fmt(float(r['value'][1]) * 1000)} |" for r in
       sorted(per_service, key=lambda r: r["metric"].get("service", "")) if r["value"][1] != "NaN"]
md += ["", "## Slowest routes (p99)", "", "| Route | p99 |", "|---|---|"]
md += [f"| {r['metric'].get('service')} {r['metric'].get('method')} {r['metric'].get('uri')} | "
       f"{fmt(float(r['value'][1]) * 1000)} |" for r in top_routes if r["value"][1] != "NaN"]
md += ["", "## Connection pool waiters (max during the run)", "", "| Service | Pool | Max pending |", "|---|---|---|"]
md += [f"| {r['metric'].get('service')} | {r['metric'].get('pool')} | {r['value'][1]} |" for r in pool]
md += ["", "## Ledger statements by total time (pg_stat_statements)", "", "| Mean ms | Calls | Query |", "|---|---|---|"]
md += [f"| {l.split(chr(9))[0]} | {l.split(chr(9))[1]} | `{l.split(chr(9))[2]}` |" for l in heavy.splitlines()
       if l.count("\t") == 2]
text = "\n".join(md) + "\n"
(OUT / "results.md").write_text(text)
print(text)
