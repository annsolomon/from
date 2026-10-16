#!/usr/bin/env python3
"""Writes verify-report.md from the artifacts that scripts/verify.sh leaves in target/verify/.

Inputs (all optional except steps.tsv):
  target/verify/steps.tsv      step<TAB>PASS|FAIL|SKIP<TAB>detail   (one line per verify step)
  target/verify/env.txt        tool versions
  target/verify/mvn.log        Maven output (reactor summary is parsed per module)
  **/target/{surefire,failsafe}-reports/TEST-*.xml
  target/verify/compose-ps.txt, infra-checks.txt, smoke.txt, k6-summary.json
Usage: report.py <mode> <repo_root> <out_file>
"""
import datetime
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except FileNotFoundError:
        return None


def fence(text, limit=120):
    lines = text.rstrip().splitlines()
    if len(lines) > limit:
        lines = lines[:limit] + [f"... ({len(lines) - limit} more lines)"]
    return "```\n" + "\n".join(lines) + "\n```\n"


def reactor(mvn_log):
    rows = []
    in_summary = False
    for line in mvn_log.splitlines():
        if "Reactor Summary" in line:
            in_summary = True
            continue
        if in_summary:
            m = re.match(r"^\[INFO\] (.+?) \.+ ?(SUCCESS|FAILURE|SKIPPED)(?: \[\s*(.+?)\])?", line)
            if m:
                rows.append((m.group(1).strip(), m.group(2), m.group(3) or ""))
            elif line.startswith("[INFO] BUILD") or line.startswith("[INFO] ---"):
                if rows:
                    break
    return rows


def tests(root):
    per_module, failures = {}, []
    pattern = os.path.join(root, "**", "target", "*-reports", "TEST-*.xml")
    for path in sorted(glob.glob(pattern, recursive=True)):
        kind = "IT" if "failsafe-reports" in path else "unit"
        module = os.path.relpath(path, root).split(os.sep + "target" + os.sep)[0]
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        stats = per_module.setdefault((module, kind), [0, 0, 0, 0])
        for case in suite.iter("testcase"):
            stats[0] += 1
            bad = case.find("failure") if case.find("failure") is not None else case.find("error")
            if case.find("skipped") is not None:
                stats[3] += 1
            elif bad is not None:
                stats[1 if bad.tag == "failure" else 2] += 1
                text = (bad.get("message") or "") + "\n" + (bad.text or "")
                failures.append((module, f"{case.get('classname')}.{case.get('name')}",
                                 "\n".join(text.strip().splitlines()[:30])))
    return per_module, failures


def main(mode, root, out):
    vdir = os.path.join(root, "target", "verify")
    steps = [l.split("\t", 2) for l in (read(os.path.join(vdir, "steps.tsv")) or "").splitlines() if l.strip()]
    overall = "PASS" if steps and all(s[1] in ("PASS", "SKIP") for s in steps) else "FAIL"
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")
    o = [f"# verify-report ({mode})\n",
         f"**Generated:** {now} · **Overall:** {overall}\n",
         "Upload this file into the next Dexter chat (MASTER_PLAN 0.1).\n"]

    env = read(os.path.join(vdir, "env.txt"))
    if env:
        o += ["\n## Environment\n", fence(env, 40)]

    o += ["\n## Steps\n", "| Step | Result | Detail |\n|---|---|---|\n"]
    for s in steps:
        s += [""] * (3 - len(s))
        o.append(f"| {s[0]} | {s[1]} | {s[2].replace('|', '/')} |\n")

    failed = [s[0] for s in steps if len(s) > 1 and s[1] == "FAIL"]
    if failed:
        o += ["\n## Logs of failed steps (last 60 lines each)\n"]
        for name in failed:
            log = read(os.path.join(vdir, name + ".log"))
            o += [f"\n### {name}\n", fence("\n".join(log.splitlines()[-60:]), 60) if log else "_no log file_\n"]

    mvn = read(os.path.join(vdir, "mvn.log"))
    if mvn:
        rows = reactor(mvn)
        o += ["\n## Modules (Maven reactor)\n"]
        if rows:
            o += ["| Module | Result | Time |\n|---|---|---|\n"]
            o += [f"| {n} | {r} | {t} |\n" for n, r, t in rows]
        else:
            o += ["No reactor summary found. Last 60 lines of mvn.log:\n", fence("\n".join(mvn.splitlines()[-60:]))]
        errs = [l for l in mvn.splitlines() if l.startswith("[ERROR]")][:40]
        if errs:
            o += ["\n### First Maven errors\n", fence("\n".join(errs))]

    per_module, failures = tests(root)
    if per_module:
        o += ["\n## Tests\n", "| Module | Kind | Tests | Failures | Errors | Skipped |\n|---|---|---|---|---|---|\n"]
        for (mod, kind), (t, f, e, s) in sorted(per_module.items()):
            o.append(f"| {mod} | {kind} | {t} | {f} | {e} | {s} |\n")
    if failures:
        o += ["\n### Failing tests (first 30 lines each)\n"]
        for mod, name, text in failures:
            o += [f"\n**{mod} :: {name}**\n", fence(text, 30)]

    for title, fname, limit in [("Containers (docker compose ps)", "compose-ps.txt", 60),
                                ("Infra checks", "infra-checks.txt", 60),
                                ("API smoke", "smoke.txt", 80)]:
        text = read(os.path.join(vdir, fname))
        if text:
            o += [f"\n## {title}\n", fence(text, limit)]

    k6 = read(os.path.join(vdir, "k6-summary.json"))
    if k6:
        try:
            m = json.loads(k6).get("metrics", {})
            dur = m.get("http_req_duration", {})
            dur = dur.get("values", dur)
            fail = m.get("http_req_failed", {})
            fail = fail.get("values", fail)
            reqs = m.get("http_reqs", {})
            reqs = reqs.get("values", reqs)
            o += ["\n## k6 smoke\n",
                  f"- requests: {reqs.get('count')}  rate/s: {reqs.get('rate')}\n",
                  f"- http_req_duration p95: {dur.get('p(95)')} ms  max: {dur.get('max')} ms\n",
                  f"- http_req_failed rate: {fail.get('rate', fail.get('value'))}\n"]
        except (ValueError, AttributeError):
            o += ["\n## k6 smoke\n", fence(k6, 40)]

    with open(out, "w", encoding="utf-8") as f:
        f.write("".join(o))
    print(f"report: {out} ({overall})")
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:4]))
