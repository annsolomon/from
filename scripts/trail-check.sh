#!/usr/bin/env bash
# Trail Live-mode check (MASTER_PLAN 0.4 "trail page exercised"): brings up core + services + trail,
# then asserts that every service answers UP *through the trail nginx proxy* (the path Live mode uses),
# plus /api/ (gateway) and the phase page. Stops the services afterwards. Exit 0 = pass.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
source scripts/lib/services.sh
PAGES=(${1:-p00-infra.html p01-core.html p02-libs.html p03-harness.html p04-identity.html p05-mfa-admin.html p06-gateway.html})
BASE=http://127.0.0.1:8099
fail=0

./scripts/run.sh core >/dev/null && ./scripts/run.sh services && ./scripts/run.sh trail >/dev/null || { echo "FAIL: could not start core/services/trail"; exit 1; }
trap './scripts/run.sh stop >/dev/null 2>&1' EXIT

check() {  # label url
  local body code
  body=$(curl -s --max-time 5 -w $'\n%{http_code}' "$2"); code=${body##*$'\n'}; body=${body%$'\n'*}
  if [[ $code == 200 && ( $body == *'"status":"UP"'* || $1 == page ) ]]; then printf 'PASS  %-10s %s\n' "$1" "$code"
  else printf 'FAIL  %-10s http=%s %s\n' "$1" "$code" "${body:0:100}"; fail=1; fi
}

for pg in "${PAGES[@]}"; do check page "$BASE/$pg"; done
check api "$BASE/api/actuator/health"
for entry in "${DEXTER_SERVICES[@]}"; do check "${entry%%:*}" "$BASE/svc/${entry%%:*}/actuator/health"; done

# P01 lab through the proxy (what p01-core.html Live mode calls)
lab=$(curl -s --max-time 5 -D /tmp/trail-p01.h -H 'X-Request-Id: trail-check-p01' "$BASE/svc/ledger/_dev/p01/money?paise=500&bps=50")
if [[ $lab == *'"resultPaise":2'* ]] && grep -qi '^x-request-id: trail-check-p01' /tmp/trail-p01.h; then printf 'PASS  %-10s %s\n' p01-lab 200
else printf 'FAIL  %-10s %s\n' p01-lab "${lab:0:100}"; fail=1; fi
pd=$(curl -s --max-time 5 "$BASE/svc/ledger/_dev/p01/errors/VERSION_CONFLICT")
if [[ $pd == *'"code":"VERSION_CONFLICT"'* ]]; then printf 'PASS  %-10s %s\n' p01-problem 412
else printf 'FAIL  %-10s %s\n' p01-problem "${pd:0:100}"; fail=1; fi

# P02 lab through the proxy (what p02-libs.html Live mode calls): start, mint, verify, idempotent POST
P2=$BASE/svc/ledger/_dev/p02
st=$(curl -s --max-time 90 "$P2/status?init=true")
if [[ $st == *'"ready":true'* ]]; then printf 'PASS  %-10s %s\n' p02-lab ready; else printf 'FAIL  %-10s %s\n' p02-lab "${st:0:160}"; fail=1; fi
tok=$(curl -s --max-time 5 -H 'Content-Type: application/json' -d '{"persona":"asha","defect":"none"}' "$P2/jwt/token" \
  | sed -E 's/.*"token":"([^"]+)".*/\1/')
who=$(curl -s --max-time 5 -H "Authorization: Bearer $tok" "$P2/jwt/whoami")
if [[ $who == *'"accepted":true'* ]]; then printf 'PASS  %-10s %s\n' p02-jwt 200; else printf 'FAIL  %-10s %s\n' p02-jwt "${who:0:160}"; fail=1; fi
k=$(cat /proc/sys/kernel/random/uuid)
c1=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H "Authorization: Bearer $tok" -H "Idempotency-Key: $k" \
  -H 'Content-Type: application/json' -d '{"title":"trail-check","amountPaise":1}' "$P2/notes")
c2=$(curl -s -D - -o /dev/null --max-time 10 -H "Authorization: Bearer $tok" -H "Idempotency-Key: $k" \
  -H 'Content-Type: application/json' -d '{"title":"trail-check","amountPaise":1}' "$P2/notes" | grep -ci '^idempotent-replayed: true')
if [[ $c1 == 201 && $c2 == 1 ]]; then printf 'PASS  %-10s %s\n' p02-idem "201 + replay"
else printf 'FAIL  %-10s %s\n' p02-idem "first=$c1 replayed=$c2"; fail=1; fi

# P03: the seed fixtures that Mock mode serves are reachable through the trail nginx and are the generated ones
for f in personas expenses categories merchants catalog-cards user-cards seed-info; do
  code=$(curl -s -o /tmp/trail-fx.json -w '%{http_code}' --max-time 5 "$BASE/fixtures/seed/$f.json")
  if [[ $code == 200 ]] && cmp -s /tmp/trail-fx.json "test-ui/fixtures/seed/$f.json"; then printf 'PASS  %-10s %s\n' "fx-$f" 200
  else printf 'FAIL  %-10s http=%s\n' "fx-$f" "$code"; fail=1; fi
done
js=$(curl -s --max-time 5 "$BASE/trail.js")
if [[ $js == *'class Pager'* && $js == *'mountIdempotencyTester'* ]]; then printf 'PASS  %-10s %s\n' trail-kit 200
else printf 'FAIL  %-10s %s\n' trail-kit "trail.js served without the P03 kit"; fail=1; fi

# P04: identity through the gateway (/api → nginx → gateway, what p04-identity.html Live mode calls since P06): JWKS, seeded demo login, /v1/me
set -a; source .env; set +a
jw=$(curl -s --max-time 5 "$BASE/api/.well-known/jwks.json")
if [[ $jw == *'"kty":"EC"'* && $jw != *'"d":'* ]]; then printf 'PASS  %-10s %s\n' p04-jwks 200; else printf 'FAIL  %-10s %s\n' p04-jwks "${jw:0:120}"; fail=1; fi
lg=$(curl -s --max-time 10 -H 'Content-Type: application/json' \
  -d "{\"email\":\"asha@demo.in\",\"password\":\"${DEMO_PASSWORD_ASHA}\",\"clientId\":\"dexter-trail\"}" "$BASE/api/v1/auth/login")
at=$(sed -nE 's/.*"accessToken":"([^"]+)".*/\1/p' <<< "$lg")
if [[ -n $at ]]; then printf 'PASS  %-10s %s\n' p04-login 200; else printf 'FAIL  %-10s %s\n' p04-login "${lg:0:160}"; fail=1; fi
me=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/me")
if [[ $me == *'"email":"asha@demo.in"'* ]]; then printf 'PASS  %-10s %s\n' p04-me 200; else printf 'FAIL  %-10s %s\n' p04-me "${me:0:160}"; fail=1; fi

# P05: admin console through the gateway (what p05-mfa-admin.html Live mode calls): password -> MFA challenge (never
# tokens), admin endpoints refuse anonymous callers (at the edge now)
al=$(curl -s --max-time 10 -H 'Content-Type: application/json' \
  -d "{\"email\":\"admin@demo.in\",\"password\":\"${DEMO_PASSWORD_ADMIN}\"}" "$BASE/api/v1/admin/auth/login")
if [[ $al == *'"mfaToken":"'* && $al != *accessToken* ]]; then printf 'PASS  %-10s %s\n' p05-admin challenge; else printf 'FAIL  %-10s %s\n' p05-admin "${al:0:160}"; fail=1; fi
an=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE/api/v1/admin/admins")
if [[ $an == 401 ]]; then printf 'PASS  %-10s %s\n' p05-anon 401; else printf 'FAIL  %-10s %s\n' p05-anon "$an"; fail=1; fi
mf=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/me/mfa")
if [[ $mf == 200 ]]; then printf 'PASS  %-10s %s\n' p05-mfa 200; else printf 'FAIL  %-10s %s\n' p05-mfa "$mf"; fail=1; fi

# P06: the gateway behind the trail proxy (what p06-gateway.html Live mode calls): request id echo, unknown path 404,
# security + rate-limit headers, spoofed identity header ignored, the bridge gone from trail.js
gh=$(curl -s -D - -o /tmp/trail-p06.json --max-time 5 -H 'X-Request-Id: trail-check-p06' "$BASE/api/v1/nope")
if [[ $gh == *' 404'* ]] && grep -qi '^x-request-id: trail-check-p06' <<< "$gh" && grep -q '"code":"NOT_FOUND"' /tmp/trail-p06.json \
  && grep -qi '^x-content-type-options: nosniff' <<< "$gh"; then printf 'PASS  %-10s %s\n' p06-404 404
else printf 'FAIL  %-10s %s\n' p06-404 "${gh:0:200}"; fail=1; fi
rh=$(curl -s -D - -o /dev/null --max-time 5 -H "Authorization: Bearer $at" -H "X-Account-Id: $(cat /proc/sys/kernel/random/uuid)" "$BASE/api/v1/me")
if [[ $rh == *' 200'* ]] && grep -qi '^ratelimit-policy: "read";q=600;w=60' <<< "$rh"; then printf 'PASS  %-10s %s\n' p06-me 200
else printf 'FAIL  %-10s %s\n' p06-me "${rh:0:200}"; fail=1; fi
js=$(curl -s --max-time 5 "$BASE/trail.js")
if [[ $js == *'const liveRoutes = [];'* ]]; then printf 'PASS  %-10s %s\n' p06-bridge gone; else printf 'FAIL  %-10s %s\n' p06-bridge "trail.js still bridges to /svc"; fail=1; fi

echo "trail-check: $([[ $fail -eq 0 ]] && echo PASS || echo FAIL)"
exit $fail
