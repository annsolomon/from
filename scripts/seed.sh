#!/usr/bin/env bash
# Deterministic mock data (MASTER_PLAN 11.1, ADR-0008). DEV ONLY.
#   ./scripts/seed.sh small|medium|large [--seed 42] [--regen] [--no-load]
#       generate target/seed/<size>-<seed>/ (reused while the jar, data and checksum are unchanged) and load it
#       into the core Postgres as each service's <svc>_owner (real tables when their migration exists, else
#       seed_stage.<table>). Needs ./scripts/run.sh core.
#   ./scripts/seed.sh check [medium] [--seed 42]
#       determinism: generates the dataset a second time and requires the identical checksum.
#   ./scripts/seed.sh fixtures
#       regenerates test-ui/fixtures/seed/*.json (small, seed 42) for the trail's mock mode.
# Time limit: SEED_MAX_SECONDS (default 600) fails generate+load if it takes longer (P03 DoD: medium < 10 min).
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

usage() { awk 'NR>1 && /^#/{sub(/^# ?/,""); print; next} NR>1{exit}' "$0" >&2; exit 2; }
CMD=${1:-}; [[ -n $CMD ]] || usage; shift
SIZE=""; SEED=42; REGEN=0; LOAD=1
case "$CMD" in
  small|medium|large) SIZE=$CMD ;;
  check) if [[ ${1:-} =~ ^(small|medium|large)$ ]]; then SIZE=$1; shift; else SIZE=medium; fi ;;
  fixtures) SIZE=small ;;
  *) usage ;;
esac
while [[ $# -gt 0 ]]; do
  case "$1" in
    --seed) SEED=${2:?--seed needs a value}; shift 2 ;;
    --regen) REGEN=1; shift ;;
    --no-load) LOAD=0; shift ;;
    *) usage ;;
  esac
done
[[ $SEED =~ ^-?[0-9]+$ ]] || { echo "seed.sh: --seed must be an integer" >&2; exit 2; }

[[ -f .env ]] || { echo "seed.sh: no .env - run: cp .env.example .env" >&2; exit 1; }
set -a; source .env; set +a
if [[ -z ${SEED_BULK_PASSWORD:-} ]]; then
  export SEED_BULK_PASSWORD='Seed-Bulk-2026!'   # .env files made before P03 lack it; same value as .env.example
  echo "seed.sh: SEED_BULK_PASSWORD not in .env - using the .env.example default"
fi

JAR=seed/target/seed.jar
build_jar() {
  local stale=0
  if [[ ! -f $JAR ]]; then stale=1
  elif [[ -n $(find seed/src seed/data seed/pom.xml -newer "$JAR" -type f -print -quit 2>/dev/null) ]]; then stale=1; fi
  if [[ $stale -eq 1 ]]; then
    echo "seed.sh: building $JAR"
    mvn -q -B -pl seed -am -DskipTests package || { echo "seed.sh: build failed" >&2; exit 1; }
  fi
}
seed_java() { java -Xmx1g -Duser.timezone=UTC -jar "$JAR" "$@"; }

checksum_of() { seed_java checksum --dir "$1" 2>/dev/null | awk '/^checksum /{print $2}'; }

generate() {  # <out dir> [extra args]
  local out=$1; shift
  rm -rf "$out"
  seed_java generate --size "$SIZE" --seed "$SEED" --out "$out" "$@"
}

build_jar
OUT=target/seed/$SIZE-$SEED

case "$CMD" in
  fixtures)
    tmp=$(mktemp -d)
    rm -rf test-ui/fixtures/seed
    generate "$tmp/ds" --fixtures test-ui/fixtures/seed && rm -rf "$tmp" || exit 1
    echo "fixtures written: test-ui/fixtures/seed ($(ls test-ui/fixtures/seed | wc -l) files) - commit them"
    exit 0 ;;
  check)
    a=$(checksum_of "$OUT")
    if [[ -z $a || $REGEN -eq 1 ]]; then generate "$OUT" >/dev/null || exit 1; a=$(checksum_of "$OUT"); fi
    b_dir=target/seed/check-$SIZE-$SEED
    generate "$b_dir" >/dev/null || exit 1
    b=$(checksum_of "$b_dir"); rm -rf "$b_dir"
    echo "run 1: $a"; echo "run 2: $b"
    if [[ -n $a && $a == "$b" ]]; then echo "seed deterministic: $SIZE seed=$SEED checksum $a"; exit 0; fi
    echo "seed NOT deterministic: $SIZE seed=$SEED"; exit 1 ;;
esac

start=$SECONDS
max=${SEED_MAX_SECONDS:-600}
reuse=0
if [[ $REGEN -eq 0 && -f $OUT/manifest.tsv && $OUT/manifest.tsv -nt $JAR ]] && [[ -n $(checksum_of "$OUT") ]]; then
  reuse=1; echo "seed.sh: reusing $OUT (checksum $(checksum_of "$OUT")); --regen to rebuild"
else
  generate "$OUT" || exit 1
fi
gen_s=$((SECONDS - start))
if [[ $LOAD -eq 1 ]]; then
  seed_java load --dir "$OUT" --replace --host "${DEXTER_PG_HOST:-127.0.0.1}" --port "${POSTGRES_PORT:-5432}" \
    || { echo "seed.sh: load failed (is ./scripts/run.sh core up?)" >&2; exit 1; }
fi
total=$((SECONDS - start))
printf 'seed.sh: %s seed=%s %s in %ds, total %ds (limit %ds)\n' "$SIZE" "$SEED" \
  "$([[ $reuse -eq 1 ]] && echo reused || echo "generated")" "$gen_s" "$total" "$max" | tee "$OUT/timing.txt"
if (( total > max )); then echo "seed.sh: FAIL - took ${total}s, limit ${max}s"; exit 1; fi
