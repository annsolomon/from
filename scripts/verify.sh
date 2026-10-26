#!/usr/bin/env bash
# Dexter v2 verification (MASTER_PLAN 10).
#   ./scripts/verify.sh static   no Maven: forbidden patterns, migration names, RLS gate, skeletons, README,
#                                trail kit spec (node --test test-ui/tests/pager.spec.js)
#   ./scripts/verify.sh unit     static + mvn -DskipITs verify + ai-parser pytest (venv in .run/venv-ai-parser)
#   ./scripts/verify.sh full     static + infra up (core) + mvn verify (ITs) + ai-parser pytest + seed medium
#                                (generate + load, < 10 min) + seed determinism + services (+ ai-parser) + api-smoke
#                                + k6 smoke
# Always writes verify-report.md at the repo root. Exit code 0 only when every step passes or is skipped.
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
MODE=${1:-static}
VDIR="$ROOT/target/verify"
REPORT="$ROOT/verify-report.md"
COMPOSE=(docker compose --env-file "$ROOT/.env" -f "$ROOT/infra/compose/docker-compose.yml")
source "$ROOT/scripts/lib/services.sh"
source "$ROOT/scripts/lib/ai_parser.sh"

case "$MODE" in static|unit|full) ;; *) echo "usage: $0 static|unit|full" >&2; exit 2 ;; esac
rm -rf "$VDIR"; mkdir -p "$VDIR"
: > "$VDIR/steps.tsv"

record() { printf '%s\t%s\t%s\n' "$1" "$2" "${3:-}" >> "$VDIR/steps.tsv"; printf '%-34s %s %s\n' "$1" "$2" "${3:-}"; }
# run_step <name> <fn> : PASS if fn returns 0; log in target/verify/<name>.log; last log line = detail
run_step() {
  local name=$1; shift
  if "$@" > "$VDIR/$name.log" 2>&1; then record "$name" PASS "$(tail -n1 "$VDIR/$name.log")"
  else record "$name" FAIL "see target/verify/$name.log: $(tail -n1 "$VDIR/$name.log")"; fi
}
skip() { record "$1" SKIP "$2"; }

CODE_DIRS=(services libs contracts seed)
existing_code_dirs() { local d; for d in "${CODE_DIRS[@]}"; do [[ -d $d ]] && echo "$d"; done; }

# ------------------------------------------------------------------ static checks
check_forbidden_patterns() {
  local dirs; mapfile -t dirs < <(existing_code_dirs)
  local hits=0 label regex flags inc
  # label | grep flags | regex | include globs
  local rules=(
    "jpa-import|-E|jakarta\.persistence|org\.hibernate|org\.springframework\.data\.jpa|JpaRepository|@Entity|*.java *.kt"
    "offset-paging|-E|OFFSET[[:space:]]|\b[Oo]ffset[[:space:]]+(:|\?|[0-9])|*.java *.sql"
    "double-money|-E|\b[Dd]ouble[[:space:]]+[A-Za-z_]*([Aa]mount|[Pp]aise|[Pp]rice|[Ff]ee|[Bb]alance)|*.java *.kt"
    "float|-Ei|\bfloat[[:space:]]|*.java *.sql *.kt"
    "select-star|-Ei|select[[:space:]]+\*|*.java *.sql"
    "block-call|-E|\.block\(\)|*.java"
    "bypassrls|-E|\bBYPASSRLS\b|*.java *.sql *.tmpl *.sh"
    "println|-E|System\.out\.println|*.java"
  )
  for rule in "${rules[@]}"; do
    IFS='|' read -r label flags rest <<< "$rule"
    inc=${rest##*|}; regex=${rest%|*}
    local incs=(); for g in $inc; do incs+=(--include="$g"); done
    local out
    out=$(grep -rn "$flags" "${incs[@]}" --exclude-dir=target -- "$regex" "${dirs[@]}" infra 2>/dev/null)
    if [[ -n $out ]]; then echo "[$label]"; echo "$out"; hits=$((hits + $(wc -l <<< "$out"))); fi
  done
  # PASSWORD literal: migrations only
  local mig; mig=$(grep -rn --include='*.sql' -E "PASSWORD[[:space:]]+'" services 2>/dev/null | grep '/db/migration/')
  if [[ -n $mig ]]; then echo "[password-literal-in-migration]"; echo "$mig"; hits=$((hits + $(wc -l <<< "$mig"))); fi
  echo "forbidden pattern hits: $hits"
  [[ $hits -eq 0 ]]
}

check_migration_names() {
  local bad=0 n=0 f
  while IFS= read -r f; do
    n=$((n+1)); local b; b=$(basename "$f")
    if [[ ! $b =~ ^V[0-9]{12}__[a-z0-9_]+\.sql(\.conf)?$ ]]; then echo "bad migration name: $f"; bad=$((bad+1)); fi
  done < <(find services -path '*/src/main/resources/db/migration/*' -type f 2>/dev/null)
  echo "migrations checked: $n, bad: $bad"
  [[ $bad -eq 0 ]]
}

check_rls_gate() { python3 -m unittest -q scripts/lib/test_rls_check.py && python3 scripts/lib/rls_check.py services; }

check_trail_isolation() {
  # services + libs only: seed/ legitimately exports test-ui fixtures (MASTER_PLAN 11.1)
  local out; out=$(grep -rn --exclude-dir=target -e 'test-ui' services libs 2>/dev/null)
  [[ -z $out ]] || { echo "$out"; echo "services/libs must never reference test-ui"; return 1; }
  echo "no service or lib references test-ui"
}

check_skeletons() {
  local bad=0 m
  local modules
  modules=$(python3 - <<'PY'
import xml.etree.ElementTree as ET
ns={'m':'http://maven.apache.org/POM/4.0.0'}
print(" ".join(e.text for e in ET.parse('pom.xml').getroot().findall('m:modules/m:module', ns)))
PY
) || { echo "could not read modules from pom.xml"; return 1; }
  [[ -n $modules ]] || { echo "no modules found in pom.xml"; return 1; }
  for m in $modules; do
    [[ -f $m/pom.xml ]] || { echo "module listed but missing: $m"; bad=$((bad+1)); }
  done
  local entry svc
  for entry in "${DEXTER_SERVICES[@]}"; do
    svc=${entry%%:*}
    ls services/$svc/src/main/java/in/dexter/$svc/*Application.java >/dev/null 2>&1 || { echo "$svc: no Application class"; bad=$((bad+1)); }
    [[ -f services/$svc/src/main/resources/application.yml ]] || { echo "$svc: no application.yml"; bad=$((bad+1)); }
    ls services/$svc/src/test/java/in/dexter/$svc/*Test.java >/dev/null 2>&1 || { echo "$svc: no smoke test"; bad=$((bad+1)); }
    [[ -f services/$svc/http/$svc.http ]] || { echo "$svc: no .http file"; bad=$((bad+1)); }
  done
  echo "module/service skeleton problems: $bad"
  [[ $bad -eq 0 ]]
}

check_xml_wellformed() {
  python3 - <<'PY'
import glob, sys, xml.etree.ElementTree as ET
bad = 0
files = [f for f in glob.glob('**/pom.xml', recursive=True) if '/target/' not in f]
for f in files:
    try: ET.parse(f)
    except ET.ParseError as e: print(f"{f}: {e}"); bad += 1
print(f"poms parsed: {len(files)}, malformed: {bad}")
sys.exit(1 if bad else 0)
PY
}

check_shell_syntax() {
  local bad=0 f
  while IFS= read -r f; do bash -n "$f" || { echo "syntax error: $f"; bad=$((bad+1)); }; done \
    < <(find scripts infra .devcontainer -name '*.sh' -type f 2>/dev/null)
  echo "shell syntax errors: $bad"
  [[ $bad -eq 0 ]]
}

check_readme() {
  local f=BUILD_README.md miss=0 i
  [[ -f $f ]] || { echo "BUILD_README.md missing"; return 1; }
  grep -q '^## 1\. Phase status' "$f" || { echo "status section missing"; miss=1; }
  for i in $(seq -w 0 19); do grep -qE "^\| P$i \|" "$f" || { echo "status row P$i missing"; miss=1; }; done
  echo "BUILD_README status table present"
  [[ $miss -eq 0 ]]
}

check_trail_pager() {
  # MASTER_PLAN P03: the trail kit's pager/mock router/idempotency spec. Plain node, no npm install.
  command -v node >/dev/null || { echo "node not installed (devcontainer feature 'node'; apt-get install nodejs)"; return 1; }
  node --test test-ui/tests/pager.spec.js 2>&1 | tail -n 12
  local rc=${PIPESTATUS[0]}
  echo "pager.spec.js (node $(node --version)): $([[ $rc -eq 0 ]] && echo PASS || echo FAIL)"
  return "$rc"
}

check_compose_config() { "${COMPOSE[@]}" --profile core --profile obs --profile trail config -q && echo "compose config valid"; }

# ------------------------------------------------------------------ environment
write_env() {
  {
    echo "mode: $MODE"; echo "git: $(git rev-parse --short HEAD 2>/dev/null || echo 'n/a')"
    echo "os: $(uname -srm)"
    echo "java: $(java -version 2>&1 | head -n1 || echo missing)"
    echo "maven: $(mvn -v 2>/dev/null | head -n1 || echo missing)"
    echo "docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo missing)"
    echo "compose: $(docker compose version --short 2>/dev/null || echo missing)"
    echo "k6: $(k6 version 2>/dev/null | head -n1 || echo missing)"
    echo "python: $(python3 --version 2>&1)"
  } > "$VDIR/env.txt"
}

# ------------------------------------------------------------------ unit/full pieces
need_env_file() { [[ -f "$ROOT/.env" ]] || { echo "no .env - run: cp .env.example .env"; return 1; }; }

mvn_unit() { mvn -B -DskipITs verify 2>&1 | tee "$VDIR/mvn.log" | tail -n 5; return "${PIPESTATUS[0]}"; }
mvn_full() { mvn -B verify 2>&1 | tee "$VDIR/mvn.log" | tail -n 5; return "${PIPESTATUS[0]}"; }

images_pull() {
  # Sequential `docker pull` per image, not `compose pull`: on this Codespace (containerd image store)
  # parallel compose pulls ended in "No such image" while a plain docker pull of the same tag succeeded.
  local img failed=() n=0
  for img in $("${COMPOSE[@]}" --profile core config --images 2>/dev/null | sort -u); do
    n=$((n+1))
    if docker image inspect "$img" >/dev/null 2>&1; then echo "present  $img"; continue; fi
    local out; out=$(docker pull "$img" 2>&1)
    if [[ $? -eq 0 ]]; then echo "pulled   $img"
    else echo "FAILED   $img"; echo "$out" | tail -n 5 | sed 's/^/    /'; failed+=("$img"); fi
  done
  if [[ ${#failed[@]} -gt 0 ]]; then
    echo "--- docker info (registry / proxy / storage) ---"
    docker info 2>&1 | grep -iE 'registry|mirror|proxy|storage driver|driver-type|server version' || true
    echo "images failed: ${#failed[@]}/$n: ${failed[*]}"
    return 1
  fi
  echo "core images ready: $n/$n"
}

infra_up() {
  local rc=0
  # Start from a clean slate so stale containers from earlier runs can't poison this one
  # (Codespaces run 5: "RWLayer of container ... is unexpectedly nil" on the containerd image store).
  # Volumes are dropped too, so postgres role init always matches the current .env. KEEP_VOLUMES=1 opts out.
  local down=(down --remove-orphans --timeout 20)
  [[ ${KEEP_VOLUMES:-0} == 1 ]] || down+=(-v)
  echo "reset: docker compose ${down[*]}"
  "${COMPOSE[@]}" --profile core --profile obs --profile trail "${down[@]}" 2>&1 | tail -n 3
  "$ROOT/scripts/run.sh" core || rc=$?
  "${COMPOSE[@]}" --profile core ps -a > "$VDIR/compose-ps.txt" 2>&1
  if [[ $rc -ne 0 ]]; then
    echo "--- containers not healthy; last 40 log lines each ---"
    local c
    for c in $("${COMPOSE[@]}" --profile core ps -a --format '{{.Service}} {{.Health}} {{.State}}' | awk '$2!="healthy"{print $1}'); do
      echo "### $c"; "${COMPOSE[@]}" logs --no-color --tail 40 "$c" 2>&1
    done
    echo "core infra NOT healthy (see compose-ps.txt)"
    return "$rc"
  fi
  echo "core infra healthy"
}

infra_checks() {
  set -a; source "$ROOT/.env"; set +a
  local psql=("${COMPOSE[@]}" exec -T postgres psql -U "$POSTGRES_USER" -d postgres -tA -c) fail=0
  local n_dbs n_roles n_bypass
  n_dbs=$("${psql[@]}" "SELECT count(*) FROM pg_database WHERE datname IN ('$(sed "s/ /','/g" <<< "$DEXTER_DB_SERVICES")')")
  n_roles=$("${psql[@]}" "SELECT count(*) FROM pg_roles WHERE rolname ~ '^[a-z]+_(owner|app|admin|relay|readonly)\$' AND rolname <> 'pg_database_owner' AND rolname !~ '^lab_'")
  local n_lab; n_lab=$("${psql[@]}" "SELECT count(*) FROM pg_database WHERE datname = 'lab'")
  n_bypass=$("${psql[@]}" "SELECT count(*) FROM pg_roles WHERE rolbypassrls AND NOT rolsuper")
  local expect_dbs; expect_dbs=$(wc -w <<< "$DEXTER_DB_SERVICES")
  {
    echo "service databases: $n_dbs (expected $expect_dbs)"
    echo "service roles: $n_roles (expected $((expect_dbs * 5)))"
    echo "non-superuser roles with row-security bypass: $n_bypass (expected 0)"
    echo "dev lab database (P02 trail lab): $n_lab (expected 1)"
    echo "ledger_app settings: $("${psql[@]}" "SELECT array_to_string(rolconfig, '; ') FROM pg_roles WHERE rolname='ledger_app'")"
    local app_pw="${PG_PW_LEDGER_APP:-$PG_PW_APP}"
    local as_app=("${COMPOSE[@]}" exec -T -e PGPASSWORD="$app_pw" postgres psql -h 127.0.0.1 -U ledger_app -tA)
    if "${as_app[@]}" -d ledger -c 'select 1' >/dev/null 2>&1; then echo "ledger_app login to ledger: OK"
    else echo "ledger_app login to ledger: FAIL"; fail=1; fi
    if "${as_app[@]}" -d identity -c 'select 1' >/dev/null 2>&1; then
      echo "cross-db isolation: FAIL (ledger_app connected to identity)"; fail=1
    else echo "cross-db isolation: OK (ledger_app cannot connect to identity)"; fi
    "${COMPOSE[@]}" exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping' | sed 's/^/redis: /'
    # Per-service Redis ACL (ADR-0004 §8): own prefixes work, foreign keys and dangerous commands are refused.
    local r_users="${DEXTER_REDIS_USERS:-gateway identity ledger catalog portfolio planning reco billing commerce audit}"
    local r_pw="${REDIS_PW_LEDGER:-${REDIS_PW_SERVICE:-dev_redis_svc_pw}}"
    local rcli=("${COMPOSE[@]}" exec -T redis redis-cli --no-auth-warning)
    local r_n; r_n=$("${rcli[@]}" -a "$REDIS_PASSWORD" ACL USERS 2>&1 | grep -c .)
    local r_expect; r_expect=$(( $(printf '%s\n' $r_users lab | sort -u | wc -l) + 1 ))   # + default; lab = dev trail lab
    if [[ $r_n -eq $r_expect ]]; then echo "redis acl users: $r_n OK"
    else echo "redis acl users: FAIL ($r_n, expected $r_expect)"; fail=1; fi
    local as_ledger=("${rcli[@]}" --user ledger --pass "$r_pw")
    local r_own; r_own=$("${as_ledger[@]}" SET ledger:v1:acl-check 1 PX 5000 2>&1)
    if [[ $r_own == *OK* ]]; then echo "redis ledger user, own prefix: OK"; else echo "redis ledger user, own prefix: FAIL ($r_own)"; fail=1; fi
    local denied
    for denied in "GET gen:catalog:global:rules" "SET catalog:v1:x 1" "KEYS *" "FLUSHALL" "CONFIG GET maxmemory" "DEL ledger:v1:acl-check"; do
      local r_args; read -ra r_args <<< "$denied"   # read -a: no glob expansion of '*'
      local r_out; r_out=$("${as_ledger[@]}" "${r_args[@]}" 2>&1)
      if [[ $r_out == *NOPERM* ]]; then echo "redis ledger user, '$denied': refused OK"
      else echo "redis ledger user, '$denied': FAIL (not refused: ${r_out:0:80})"; fail=1; fi
    done
    local deadline=$((SECONDS + 90)) kafka_ok=0
    while (( SECONDS < deadline )); do
      if "${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
        kafka_ok=1; break; fi
      sleep 3
    done
    local s3="http://127.0.0.1:${OBJECTSTORE_PORT:-8333}"
    if curl -fsS --max-time 5 -o /dev/null "$s3/healthz"; then echo "objectstore: S3 endpoint up on :${OBJECTSTORE_PORT:-8333}"
    else echo "objectstore: FAIL (S3 endpoint not reachable)"; fail=1; fi
    # P12 (ADR-0017): S3 identities are on, so anonymous requests are refused (statements must never be public)
    local anon; anon=$(curl -s --max-time 5 -o /dev/null -w '%{http_code}' -X PUT "$s3/dexter-anon-check")
    if [[ $anon == 403 ]]; then echo "objectstore: anonymous PUT refused (403) OK"
    else echo "objectstore: FAIL (anonymous PUT answered $anon, expected 403)"; fail=1; fi
    if [[ $kafka_ok -eq 1 ]]; then echo "kafka: topics API reachable"
    else echo "kafka: FAIL (topics API not reachable within 90s)"; "${COMPOSE[@]}" logs --no-color --tail 30 kafka; fail=1; fi
  } > "$VDIR/infra-checks.txt" 2>&1   # no pipe: fail=1 must survive (a pipeline would run this in a subshell)
  cat "$VDIR/infra-checks.txt"
  [[ $n_dbs -eq $expect_dbs && $n_roles -eq $((expect_dbs * 5)) && $n_bypass -eq 0 && $n_lab -eq 1 && $fail -eq 0 ]]
}

services_up() { "$ROOT/scripts/run.sh" services; }
services_down() { "$ROOT/scripts/run.sh" stop >/dev/null 2>&1 || true; }
api_smoke() { SMOKE_OUT="$VDIR/smoke.txt" "$ROOT/scripts/api-smoke.sh"; }
# P03 (MASTER_PLAN 11.1): medium seed generated + loaded into the core Postgres within 10 min, then determinism
# (a second generation must give the identical checksum). Uses the jar mvn verify just built.
seed_medium() { SEED_MAX_SECONDS=600 "$ROOT/scripts/seed.sh" medium --regen 2>&1 | tail -n 20; return "${PIPESTATUS[0]}"; }
seed_determinism() { "$ROOT/scripts/seed.sh" check medium 2>&1 | tail -n 5; return "${PIPESTATUS[0]}"; }
# P04 (ADR-0008 step owed by P04): once the services ran their Flyway migrations, the small seed loads into the real
# tables (identity.accounts), so the demo accounts can sign in (api-smoke, trail-check).
seed_small_real() { "$ROOT/scripts/seed.sh" small 2>&1 | tail -n 8; return "${PIPESTATUS[0]}"; }
k6_smoke() {
  k6 run --quiet --summary-export "$VDIR/k6-summary.json" "$ROOT/perf/smoke.js" && echo "k6 smoke thresholds met"
}

# ------------------------------------------------------------------ run
echo "== verify.sh $MODE =="
# python3 runs the RLS gate, the pom checks, the smoke-test lab walk and the report. Some Codespace
# images (devcontainers base:ubuntu) ship without it; install it once instead of failing three steps.
if ! command -v python3 >/dev/null; then
  echo "python3 missing - installing (apt)"
  if sudo -n true 2>/dev/null; then sudo apt-get update -qq >/dev/null && sudo apt-get install -y -qq python3 >/dev/null; fi
  command -v python3 >/dev/null || { echo "FATAL: python3 is required (sudo apt-get install -y python3)"; exit 2; }
fi
write_env
run_step static.forbidden-patterns check_forbidden_patterns
run_step static.migration-names    check_migration_names
run_step static.rls-gate           check_rls_gate
run_step static.trail-isolation    check_trail_isolation
run_step static.skeletons          check_skeletons
run_step static.poms-wellformed    check_xml_wellformed
run_step static.shell-syntax       check_shell_syntax
run_step static.readme             check_readme
run_step static.trail-pager        check_trail_pager
if command -v docker >/dev/null 2>&1 && [[ -f .env ]]; then run_step static.compose-config check_compose_config
else skip static.compose-config "docker or .env not available"; fi

if [[ $MODE == unit ]]; then
  if command -v mvn >/dev/null; then run_step unit.maven mvn_unit; else record unit.maven FAIL "mvn not found"; fi
  run_step unit.ai-parser parser_test
fi

if [[ $MODE == full ]]; then
  run_step full.env-file need_env_file
  # k6 is needed by the last step. Install it up front (user-local fallback, no sudo needed) so a fresh
  # Codespace, or a repo whose .devcontainer is not at the root, still gets a complete report.
  export PATH="$PATH:$HOME/.local/bin"
  if ! command -v k6 >/dev/null; then
    echo "k6 missing - installing via scripts/install-tools.sh"
    bash "$ROOT/scripts/install-tools.sh" > "$VDIR/k6-install.log" 2>&1 || echo "note: k6 install failed - see target/verify/k6-install.log"
    hash -r
  fi
  if command -v docker >/dev/null; then
    run_step full.images-pull images_pull
    run_step full.infra-up infra_up
    if grep -q $'full.infra-up\tPASS' "$VDIR/steps.tsv"; then run_step full.infra-checks infra_checks
    else skip full.infra-checks "infra not healthy - see full.infra-up.log"; fi
  else record full.infra-up FAIL "docker not found"; fi
  if command -v mvn >/dev/null; then run_step full.maven mvn_full; else record full.maven FAIL "mvn not found"; fi
  run_step full.ai-parser parser_test
  if grep -q $'full.maven\tPASS' "$VDIR/steps.tsv" && grep -q $'full.infra-up\tPASS' "$VDIR/steps.tsv"; then
    run_step full.seed-medium seed_medium
    run_step full.seed-determinism seed_determinism
  else
    skip full.seed-medium "maven build or infra failed"; skip full.seed-determinism "maven build or infra failed"
  fi
  if grep -q $'full.maven\tPASS' "$VDIR/steps.tsv"; then
    trap services_down EXIT
    run_step full.services-up services_up
    run_step full.seed-small seed_small_real
    run_step full.api-smoke api_smoke
    if command -v k6 >/dev/null; then run_step full.k6-smoke k6_smoke; else record full.k6-smoke FAIL "k6 not installed and auto-install failed - see target/verify/k6-install.log"; fi
    services_down
  else
    skip full.api-smoke "maven build failed"; skip full.k6-smoke "maven build failed"
  fi
fi

python3 "$ROOT/scripts/lib/report.py" "$MODE" "$ROOT" "$REPORT"
if grep -q $'\tFAIL\t' "$VDIR/steps.tsv"; then echo "RESULT: FAIL"; exit 1; fi
echo "RESULT: PASS"
