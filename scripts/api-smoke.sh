#!/usr/bin/env bash
# API smoke against running services (dev profile, see run.sh).
#   P00: every service /actuator/health == UP.
#   P02: the dev-only lab on ledger (scripts/lib/p02_smoke.py): JWT defects/rotation, Idempotency-Key
#        replay/422/409/400, outbox -> Kafka -> projection + DLQ, cache hit/miss/Path A/outage/purge.
#   P01: X-Request-Id echo/generation + coded 404 ProblemDetail on the 9 servlet services, and the
#        dev-only /_dev/p01 lab on ledger (money rounding, strict JSON, If-Match, keyset cursor).
#   P04: identity (scripts/lib/p04_smoke.py): register -> Mailpit verification email -> verify -> login -> /me PATCH
#        (If-Match) -> refresh rotation + reuse detection -> sessions -> logout; enumeration-safe answers; seeded demo
#        login; client-credentials tokens; dev key rotation. Then: identity's outbox rows are published to Kafka.
# Later phases append their checks below the P00 block. Output also goes to $SMOKE_OUT if set.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
source "$ROOT/scripts/lib/services.sh"
OUT=${SMOKE_OUT:-/dev/null}
: > "$OUT"
fail=0
log() { echo "$*" | tee -a "$OUT"; }

log "# P00 health"
for entry in "${DEXTER_SERVICES[@]}"; do
  svc=${entry%%:*}; port=${entry##*:}
  start=$(date +%s%N)
  body=$(curl -sS --max-time 3 -H "X-Request-Id: smoke-$svc" -w $'\n%{http_code}' "http://localhost:$port/actuator/health" 2>&1)
  ms=$(( ($(date +%s%N) - start) / 1000000 ))
  code=${body##*$'\n'}; body=${body%$'\n'*}
  if [[ $code == 200 && $body == *'"status":"UP"'* ]]; then log "PASS  $svc :$port  health UP  ${ms}ms"
  else log "FAIL  $svc :$port  http=$code body=${body:0:120}"; fail=1; fi
done

# ------------------------------------------------------------------ P01: dexter-web wiring + lab
# Every servlet service (all but the gateway, which gets its own filter in P06).
log "# P01 request id + ProblemDetail (9 servlet services)"
hdr() { grep -i "^$1:" | head -n1 | cut -d' ' -f2- | tr -d '\r'; }
for entry in "${DEXTER_SERVICES[@]}"; do
  svc=${entry%%:*}; port=${entry##*:}
  [[ $svc == gateway ]] && continue
  echoed=$(curl -sS -D - -o /dev/null --max-time 3 -H "X-Request-Id: smoke-p01-$svc" "http://localhost:$port/actuator/health" | hdr X-Request-Id)
  gen=$(curl -sS -D - -o /dev/null --max-time 3 "http://localhost:$port/actuator/health" | hdr X-Request-Id)
  nf=$(curl -sS --max-time 3 -w $'\n%{http_code}' -H "X-Request-Id: nf-$svc" "http://localhost:$port/v1/__nope")
  nfcode=${nf##*$'\n'}; nfbody=${nf%$'\n'*}
  if [[ $echoed == "smoke-p01-$svc" && $gen =~ ^[0-9a-f-]{36}$ && $nfcode == 404 && $nfbody == *'"code":"NOT_FOUND"'* && $nfbody == *"\"requestId\":\"nf-$svc\""* ]]; then
    log "PASS  $svc  X-Request-Id echoed + generated, 404 ProblemDetail NOT_FOUND"
  else
    log "FAIL  $svc  echoed=[$echoed] generated=[$gen] 404=[$nfcode] body=${nfbody:0:160}"; fail=1
  fi
done

# The dev-only lab (dev profile, run.sh default) on ledger exercises the real library code.
LAB=http://localhost:8082/_dev/p01
log "# P01 lab (ledger, dev profile)"
check() {  # label expected_status expected_substring curl-args...
  local label=$1 want=$2 sub=$3; shift 3
  local out code body
  out=$(curl -sS --max-time 3 -w $'\n%{http_code}' "$@"); code=${out##*$'\n'}; body=${out%$'\n'*}
  if [[ $code == "$want" && $body == *"$sub"* ]]; then log "PASS  $label  $code"
  else log "FAIL  $label  want $want/$sub got $code ${body:0:160}"; fail=1; fi
}
check "money 500p x 50bps HALF_EVEN = 2" 200 '"resultPaise":2' "$LAB/money?paise=500&bps=50"
check "money HALF_UP = 3" 200 '"resultPaise":3' "$LAB/money?paise=500&bps=50&rounding=HALF_UP"
J=(-H 'Content-Type: application/json' -X POST "$LAB/strict-json")
check "strict json ok" 200 '"amountPaise":5' "${J[@]}" -d '{"name":"a","amountPaise":5}'
check "unknown field" 400 '"code":"UNKNOWN_FIELD"' "${J[@]}" -d '{"name":"a","amountPaise":5,"admin":true}'
check "duplicate field" 400 '"code":"DUPLICATE_FIELD"' "${J[@]}" -d '{"name":"a","name":"b","amountPaise":5}'
check "malformed json" 400 '"code":"MALFORMED_JSON"' "${J[@]}" -d '{"name":'
check "validation errors[]" 400 '"field":"amountPaise"' "${J[@]}" -d '{"name":"","amountPaise":0}'
check "If-Match missing" 428 '"code":"IF_MATCH_REQUIRED"' -X PUT "$LAB/doc"
check "If-Match weak" 400 '"code":"IF_MATCH_INVALID"' -X PUT -H 'If-Match: W/"0"' "$LAB/doc"
v=$(curl -sS --max-time 3 "$LAB/doc" | sed -E 's/.*"version":([0-9]+).*/\1/')
check "If-Match current" 200 "\"version\":$((v + 1))" -X PUT -H "If-Match: \"$v\"" "$LAB/doc"
check "If-Match stale" 412 '"code":"VERSION_CONFLICT"' -X PUT -H "If-Match: \"$v\"" "$LAB/doc"
check "limit=101" 400 '"code":"LIMIT_INVALID"' "$LAB/items?limit=101"
check "limit=0" 400 '"code":"LIMIT_INVALID"' "$LAB/items?limit=0"
check "garbage cursor" 400 '"code":"CURSOR_INVALID"' "$LAB/items?cursor=abc.def"
# walk all pages: 50 then 25s, every id exactly once (120 items, 40 share one timestamp)
walk=$(python3 - "$LAB" <<'PY'
import json, sys, urllib.request, urllib.parse
lab = sys.argv[1]; seen = []; cursor = None; limit = 50
while True:
    q = {"limit": limit} | ({"cursor": cursor} if cursor else {})
    body = json.load(urllib.request.urlopen(lab + "/items?" + urllib.parse.urlencode(q), timeout=3))
    seen += [d["id"] for d in body["data"]]
    if not body["page"]["hasMore"]:
        ok = body["page"]["nextCursor"] is None
        break
    cursor = body["page"]["nextCursor"]; limit = 25
print("OK" if ok and len(seen) == 120 and len(set(seen)) == 120 else f"BAD {len(seen)} {len(set(seen))}")
PY
)
if [[ $walk == OK ]]; then log "PASS  keyset walk 120 rows, no dups, last page nextCursor=null"; else log "FAIL  keyset walk: $walk"; fail=1; fi
cur=$(curl -sS --max-time 3 "$LAB/items?limit=5&category=food" | sed -E 's/.*"nextCursor":"([^"]+)".*/\1/')
check "cursor filter mismatch" 400 '"code":"CURSOR_FILTER_MISMATCH"' "$LAB/items?category=travel&cursor=$cur"
tampered="${cur:0:10}$([[ ${cur:10:1} == A ]] && echo B || echo A)${cur:11}"
check "tampered cursor" 400 '"code":"CURSOR_INVALID"' "$LAB/items?category=food&cursor=$tampered"

# ------------------------------------------------------------------ P02: libraries lab (ledger, dev profile)
# Real Postgres (db lab), Kafka and Redis (user lab) through dexter-lab: JWT, Idempotency-Key, outbox -> Kafka ->
# projection + DLQ, cache. The lab starts on the first call (topics, relay, consumers).
log "# P02 lab (ledger /_dev/p02)"
p02=$(python3 "$ROOT/scripts/lib/p02_smoke.py" "http://localhost:8082/_dev/p02" 2>&1); p02rc=$?
while IFS= read -r line; do log "$line"; done <<< "$p02"
[[ $p02rc -eq 0 ]] || fail=1

# ------------------------------------------------------------------ P04: identity
log "# P04 identity (:8081, Mailpit :8025)"
if [[ -f "$ROOT/.env" ]]; then set -a; source "$ROOT/.env"; set +a; fi
p04=$(python3 "$ROOT/scripts/lib/p04_smoke.py" "http://localhost:8081" "http://127.0.0.1:8025" 2>&1); p04rc=$?
while IFS= read -r line; do log "$line"; done <<< "$p04"
[[ $p04rc -eq 0 ]] || fail=1
# Outbox -> Kafka (MASTER_PLAN 6.3): every identity event written above is published by the relay within 30 s.
COMPOSE=(docker compose --env-file "$ROOT/.env" -f "$ROOT/infra/compose/docker-compose.yml")
outbox() { "${COMPOSE[@]}" exec -T postgres psql -U "${POSTGRES_USER:-postgres}" -d identity -tA -c "$1" 2>/dev/null | tr -d '[:space:]'; }
pending=x; published=0
for _ in $(seq 1 30); do
  pending=$(outbox "SELECT count(*) FROM identity.outbox WHERE published_at IS NULL")
  published=$(outbox "SELECT count(*) FROM identity.outbox WHERE published_at IS NOT NULL")
  [[ $pending == 0 && ${published:-0} -gt 0 ]] && break
  sleep 1
done
if [[ $pending == 0 && ${published:-0} -gt 0 ]]; then log "PASS  identity outbox -> Kafka: $published published, 0 pending"
else log "FAIL  identity outbox -> Kafka: pending=$pending published=$published"; fail=1; fi
topics=$("${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null)
if [[ $topics == *dexter.identity.account.v1* && $topics == *dexter.identity.session.v1* && $topics == *dexter.identity.security.v1* ]]; then
  log "PASS  identity topics exist (account, session, security)"
else log "FAIL  identity topics missing: $(tr '\n' ' ' <<< "$topics")"; fail=1; fi

log "# summary: $([[ $fail -eq 0 ]] && echo PASS || echo FAIL)"
exit $fail
