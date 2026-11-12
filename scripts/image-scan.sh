#!/usr/bin/env bash
# P19 (ADR-0027): builds the ten service images (infra/docker/service.Dockerfile, distroless, non-root) and scans
# each with Trivy for HIGH/CRITICAL vulnerabilities that have a fix (OS packages and the jars inside). Waivers:
# infra/security/trivyignore.yaml. Trivy runs pinned, from its image, on a `docker save` tar - no Docker socket, no
# credentials in the container (ADR-0027: the March 2026 Trivy supply-chain incident).
# Reports: target/verify/trivy/<svc>.txt + summary.tsv. Exit 1 on any finding or any image that could not be built.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/lib/services.sh"
TRIVY=${TRIVY_IMAGE:-aquasec/trivy:0.72.0}
OUT="$ROOT/target/verify/trivy"; TARS="$ROOT/target/images"
CACHE="${TRIVY_CACHE:-$HOME/.cache/dexter-trivy}"
rm -rf "$OUT"; mkdir -p "$OUT" "$TARS" "$CACHE"
TAG=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo local)
: > "$OUT/summary.tsv"
fail=0 built=0
for entry in "${DEXTER_SERVICES[@]}"; do
  svc=${entry%%:*}
  jar="$ROOT/services/$svc/target/app.jar"
  [[ -f $jar ]] || { echo "FAIL $svc: no $jar (run mvn package first)"; fail=1; continue; }
  ctx=$(mktemp -d); cp "$jar" "$ctx/app.jar"
  if ! timeout -k 10 600 docker build -q -f "$ROOT/infra/docker/service.Dockerfile" -t "dexter/$svc:$TAG" "$ctx" \
      > "$OUT/$svc.build.log" 2>&1; then
    echo "FAIL $svc: image build failed"; tail -n 10 "$OUT/$svc.build.log"; fail=1; rm -rf "$ctx"; continue
  fi
  rm -rf "$ctx"; built=$((built + 1))
  docker save -o "$TARS/$svc.tar" "dexter/$svc:$TAG"
  # user + group of the image: must not be root
  user=$(docker image inspect -f '{{.Config.User}}' "dexter/$svc:$TAG")
  timeout -k 10 600 docker run --rm --network bridge -v "$TARS:/scan:ro" -v "$CACHE:/root/.cache/trivy" \
    -v "$ROOT/infra/security:/cfg:ro" "$TRIVY" image --quiet --input "/scan/$svc.tar" \
    --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --ignorefile /cfg/trivyignore.yaml \
    --format json --output "/root/.cache/trivy/$svc.json" > "$OUT/$svc.trivy.log" 2>&1
  rc=$?
  if [[ $rc -ne 0 || ! -f "$CACHE/$svc.json" ]]; then
    echo "FAIL $svc: trivy did not run (exit $rc)"; tail -n 10 "$OUT/$svc.trivy.log"; fail=1; continue
  fi
  mv "$CACHE/$svc.json" "$OUT/$svc.json"
  read -r n pkgs list < <(python3 - "$OUT/$svc.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
v = [x for res in (r.get("Results") or []) for x in (res.get("Vulnerabilities") or [])]
pk = {res.get("Target") for res in (r.get("Results") or []) if res.get("Vulnerabilities")}
print(len(v), len(r.get("Results") or []), ",".join(sorted({f"{x['VulnerabilityID']}({x['PkgName']} {x['InstalledVersion']}->{x.get('FixedVersion','')})" for x in v}))[:1500] or "-")
PY
)
  printf '%s\t%s\t%s\t%s\t%s\n' "$svc" "$user" "$n" "$pkgs" "$list" >> "$OUT/summary.tsv"
  if [[ $user == "" || $user == "root" || $user == "0" ]]; then echo "FAIL $svc: image runs as root"; fail=1; fi
  if [[ $n -gt 0 ]]; then echo "FAIL $svc: $n HIGH/CRITICAL with a fix: $list"; fail=1
  else echo "ok   $svc: user $user, 0 HIGH/CRITICAL fixable ($pkgs scan targets)"; fi
done
rm -rf "$TARS"
echo "images: $built/${#DEXTER_SERVICES[@]} built; findings in target/verify/trivy/summary.tsv; $([[ $fail -eq 0 ]] && echo clean || echo NOT clean)"
exit $fail
