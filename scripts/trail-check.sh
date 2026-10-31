#!/usr/bin/env bash
# Trail Live-mode check (MASTER_PLAN 0.4 "trail page exercised"): brings up core + services + trail,
# then asserts that every service answers UP *through the trail nginx proxy* (the path Live mode uses),
# plus /api/ (gateway) and the phase page. Stops the services afterwards. Exit 0 = pass.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
source scripts/lib/services.sh
PAGES=(${1:-p00-infra.html p01-core.html p02-libs.html p03-harness.html p04-identity.html p05-mfa-admin.html p06-gateway.html p07-ledger.html p08-ledger-plus.html p09-catalog.html p10-ingestion.html p11-portfolio.html p12-statements.html p12-calc.html p13-planning.html p14-reco.html})
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

# P07: the ledger through the trail proxy (what p07-ledger.html Live mode calls): a keyset page with a cursor, daily
# totals, the categories list
ex=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/expenses?limit=50")
if [[ $ex == *'"hasMore":true'* && $ex == *'"nextCursor":"'* && $ex == *'"merchant":'* ]]; then printf 'PASS  %-10s %s\n' p07-list 200
else printf 'FAIL  %-10s %s\n' p07-list "${ex:0:200}"; fail=1; fi
d1=$(TZ=Asia/Kolkata date -d '-60 days' +%F); d2=$(TZ=Asia/Kolkata date +%F)
dt=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/expenses/daily-totals?from=$d1&to=$d2")
if [[ $dt == '{"data":['* ]]; then printf 'PASS  %-10s %s\n' p07-totals 200; else printf 'FAIL  %-10s %s\n' p07-totals "${dt:0:200}"; fail=1; fi
ct=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/categories")
if [[ $ct == *'"path":"food.cafe"'* ]]; then printf 'PASS  %-10s %s\n' p07-cats 200; else printf 'FAIL  %-10s %s\n' p07-cats "${ct:0:200}"; fail=1; fi

# P08: what p08-ledger-plus.html Live mode calls: autocomplete, calendar, summary, search
ac=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/merchants/autocomplete?q=swi")
if [[ $ac == *'"merchant":"Swiggy'* ]]; then printf 'PASS  %-10s %s\n' p08-ac 200; else printf 'FAIL  %-10s %s\n' p08-ac "${ac:0:200}"; fail=1; fi
cm=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/calendar?month=$(TZ=Asia/Kolkata date +%Y-%m)")
if [[ $cm == *'"days":['* ]]; then printf 'PASS  %-10s %s\n' p08-cal 200; else printf 'FAIL  %-10s %s\n' p08-cal "${cm:0:200}"; fail=1; fi
sm=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/summary?from=$d1&to=$d2&groupBy=category")
if [[ $sm == *'"groups":['* ]]; then printf 'PASS  %-10s %s\n' p08-sum 200; else printf 'FAIL  %-10s %s\n' p08-sum "${sm:0:200}"; fail=1; fi
se=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/expenses/search?q=swiggy")
if [[ $se == *'"page":'* ]]; then printf 'PASS  %-10s %s\n' p08-search 200; else printf 'FAIL  %-10s %s\n' p08-search "${se:0:200}"; fail=1; fi

# P09: the public catalog through the trail proxy without a token (what p09-catalog.html Live mode calls)
cc=$(curl -s --max-time 5 "$BASE/api/v1/catalog/cards?limit=24")
if [[ $cc == *'"slug":"mock-'* && $cc == *'"nextCursor":"'* ]]; then printf 'PASS  %-10s %s\n' p09-cards 200; else printf 'FAIL  %-10s %s\n' p09-cards "${cc:0:200}"; fail=1; fi
cd1=$(curl -s -D - -o /tmp/trail-p09.json --max-time 5 "$BASE/api/v1/catalog/cards/mock-saffron-millennia-cashback")
if [[ $cd1 == *' 200'* ]] && grep -qi '^etag: "' <<< "$cd1" && grep -q '"rules":\[' /tmp/trail-p09.json; then printf 'PASS  %-10s %s\n' p09-detail 200
else printf 'FAIL  %-10s %s\n' p09-detail "${cd1:0:200}"; fail=1; fi
cp=$(curl -s --max-time 5 "$BASE/api/v1/catalog/categories/potential?category=food.delivery")
if [[ $cp == *'"rateBps":'* ]]; then printf 'PASS  %-10s %s\n' p09-potent 200; else printf 'FAIL  %-10s %s\n' p09-potent "${cp:0:200}"; fail=1; fi

# P10: what p10-ingestion.html Live mode calls without an admin token (admin flow: api-smoke P10 + ITs) and the
# mock source fixture the page serves in Mock mode
for p in /api/v1/admin/review /api/v1/admin/ingestion/sources; do
  rc=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE$p")
  if [[ $rc == 401 ]]; then printf 'PASS  %-10s %s %s\n' p10-anon "$p" 401; else printf 'FAIL  %-10s %s %s\n' p10-anon "$p" "$rc"; fail=1; fi
done
fx=$(curl -s --max-time 5 "$BASE/fixtures/p10-source.json")
if [[ $fx == *'"cardSlug": "mock-saffron-millennia-cashback"'* ]]; then printf 'PASS  %-10s %s\n' p10-fixture 200
else printf 'FAIL  %-10s %s\n' p10-fixture "${fx:0:120}"; fail=1; fi

# P11: what p11-portfolio.html Live mode calls as asha (writes: api-smoke P11 + ITs)
rc=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE/api/v1/cards")
if [[ $rc == 401 ]]; then printf 'PASS  %-10s %s\n' p11-anon 401; else printf 'FAIL  %-10s %s\n' p11-anon "$rc"; fail=1; fi
uc=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/cards?limit=50")
if [[ $uc == *'"productSlug":"mock-'* && $uc == *'"currentCycle":{'* ]]; then printf 'PASS  %-10s %s\n' p11-cards 200
else printf 'FAIL  %-10s %s\n' p11-cards "${uc:0:200}"; fail=1; fi
rb=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/rewards/balance")
if [[ $rb == *'"points":'* && $rb == *'"cards":['* ]]; then printf 'PASS  %-10s %s\n' p11-rewards 200
else printf 'FAIL  %-10s %s\n' p11-rewards "${rb:0:200}"; fail=1; fi
rp=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/portfolio/report?period=year")
if [[ $rp == *'"period":"year"'* && $rp == *'"cards":['* ]]; then printf 'PASS  %-10s %s\n' p11-report 200
else printf 'FAIL  %-10s %s\n' p11-report "${rp:0:200}"; fail=1; fi
ls=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/links/suggestions?limit=10")
if [[ $ls == *'"page":'* ]]; then printf 'PASS  %-10s %s\n' p11-links 200; else printf 'FAIL  %-10s %s\n' p11-links "${ls:0:200}"; fail=1; fi

# P12: what p12-statements.html Live mode calls as asha (the full upload -> parser -> confirm flow: api-smoke P12)
rc=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE/api/v1/statements/uploads")
if [[ $rc == 401 ]]; then printf 'PASS  %-10s %s\n' p12-anon 401; else printf 'FAIL  %-10s %s\n' p12-anon "$rc"; fail=1; fi
su=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/statements/uploads?limit=5")
if [[ $su == *'"page":'* && $su == *'"data":'* ]]; then printf 'PASS  %-10s %s\n' p12-uploads 200
else printf 'FAIL  %-10s %s\n' p12-uploads "${su:0:200}"; fail=1; fi
# the browser PUTs the statement straight to the object store: its CORS preflight must allow the trail origin
pf=$(curl -s -o /dev/null -D - -X OPTIONS "http://127.0.0.1:${OBJECTSTORE_PORT:-8333}/dexter-statements/x.pdf" \
  -H "Origin: $BASE" -H 'Access-Control-Request-Method: PUT' -H 'Access-Control-Request-Headers: content-type')
if grep -qi '^access-control-allow-methods:.*PUT' <<< "$pf"; then printf 'PASS  %-10s %s\n' p12-cors PUT
else printf 'FAIL  %-10s %s\n' p12-cors "$(head -c 200 <<< "$pf")"; fail=1; fi
if curl -fsS --max-time 5 "http://localhost:${PORT_AI_PARSER:-8090}/health" | grep -q '"state":"consuming"'; then
  printf 'PASS  %-10s %s\n' p12-parser consuming
else printf 'FAIL  %-10s %s\n' p12-parser "$(curl -s --max-time 5 "http://localhost:${PORT_AI_PARSER:-8090}/health" | head -c 200)"; fail=1; fi

# P12b: what p12-calc.html Live mode calls as asha. The numbers are the ported golden fixtures (ADR-0018): Live
# must answer exactly what the page's Mock mode replays, or the page is lying about the engines.
calcpost() {  # body path
  curl -s --max-time 30 -H "Authorization: Bearer $at" -H 'Content-Type: application/json' -d "$1" "$BASE/api/v1/calc/$2"
}
rc=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X POST -H 'Content-Type: application/json' \
  -d '{"principalPaise":100000,"annualRateBps":1500,"tenureMonths":12}' "$BASE/api/v1/calc/emi")
if [[ $rc == 401 ]]; then printf 'PASS  %-10s %s\n' p12-calc-anon 401
else printf 'FAIL  %-10s %s\n' p12-calc-anon "$rc"; fail=1; fi
ce=$(calcpost '{"principalPaise":10000000,"annualRateBps":1500,"tenureMonths":12,"processingFeePaise":19900,"firstDueDate":"2026-11-05"}' emi)
if [[ $ce == *'"emiPaise":902583'* && $ce == *'"totalInterestPaise":830997'* ]]; then printf 'PASS  %-10s %s\n' p12-calc-emi 200
else printf 'FAIL  %-10s %s\n' p12-calc-emi "${ce:0:200}"; fail=1; fi
cm=$(calcpost '{"terms":{"aprBps":4200,"cashAdvanceAprBps":4200,"paymentAllocationOrder":["retail_purchases","finance_charges","fees"]},"minimumDueTerms":{"formulaType":"HIGHER_OF","percentBps":500,"flatAmountPaise":20000},"startingPrincipalPaise":1000000,"cycleLengthDays":30}' scenarios/minimum-payment)
if [[ $cm == *'"cyclesToPayoff":141'* && $cm == *'"totalExtraPaidPaise":3082088'* ]]; then printf 'PASS  %-10s %s\n' p12-calc-min 141
else printf 'FAIL  %-10s %s\n' p12-calc-min "${cm:0:200}"; fail=1; fi
cx=$(calcpost '{"terms":{"aprBps":3600,"cashAdvanceAprBps":3600,"paymentAllocationOrder":["finance_charges","retail_purchases"]},"minimumDueTerms":{"formulaType":"HIGHER_OF","percentBps":500,"flatAmountPaise":20000},"currentBalancePaise":600000,"cycleLengthDays":30,"targetCycles":[3,6,12]}' exit-paths)
if [[ $cx == *'"requiredPaymentPerCyclePaise":214101'* && $cx == *'"recommendedPath":"targeted_payoff_3cycles"'* ]]; then
  printf 'PASS  %-10s %s\n' p12-calc-exit 200
else printf 'FAIL  %-10s %s\n' p12-calc-exit "${cx:0:200}"; fail=1; fi

# P13: what p13-planning.html Live mode drives. The page's claim is that an expense recorded in the LEDGER moves a
# planning budget bar, so this walks the whole path - budget, expense, outbox, relay, Kafka, consumer - and then
# the shared EMI engine's golden figure (Rs 8,884.88) through /v1/liabilities.
rc=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE/api/v1/budgets/status")
if [[ $rc == 401 ]]; then printf 'PASS  %-10s %s\n' p13-anon 401
else printf 'FAIL  %-10s %s\n' p13-anon "$rc"; fail=1; fi
pday=$(TZ=Asia/Kolkata date +%Y-%m-%d)
# The ledger refuses a category outside its visible tree, so these are seeded paths. A budget created now starts
# at zero (planning does not backfill), so only the expense recorded below counts towards it.
pcat=health
pexp=health.doctor
# An idempotent create replays its stored response, which the engine keeps as jsonb and reads back with
# `::text` - so Postgres renders it with a space after each colon. Parse the JSON instead of matching substrings.
jget() {  # <json on stdin> <field> [field...] -> the values, space-separated ("" if the body is not that shape)
  python3 -c '
import json, sys
d = json.load(sys.stdin)
print(" ".join(str(d[f]) for f in sys.argv[1:]))
' "$@" 2>/dev/null
}
pb=$(curl -s --max-time 10 -H "Authorization: Bearer $at" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
  -d "{\"name\":\"trail-check\",\"period\":\"monthly\",\"categoryPath\":\"$pcat\",\"limitPaise\":200000,\"startsOn\":\"${pday%-*}-01\"}" \
  "$BASE/api/v1/budgets")
bid=$(jget id <<< "$pb")
bshape=$(jget status alertThresholds limitPaise <<< "$pb")
if [[ -n $bid && $bshape == "active [50, 80, 100] 200000" ]]; then
  printf 'PASS  %-10s %s\n' p13-budget 201
else printf 'FAIL  %-10s %s\n' p13-budget "${pb:0:200}"; fail=1; fi
curl -s -o /dev/null --max-time 10 -H "Authorization: Bearer $at" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
  -d "{\"amountPaise\":180000,\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"merchant\":\"Trail cafe\",\"categoryPath\":\"$pexp\",\"paymentMethod\":\"upi\"}" \
  "$BASE/api/v1/expenses"
spent=""
for _ in $(seq 1 30); do
  st=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/budgets/status")
  spent=$(printf '%s' "$st" | python3 -c '
import json, sys
for r in json.load(sys.stdin):
    if r["budget"]["id"] == sys.argv[1] and r.get("current"):
        c = r["current"]
        print(c["spentPaise"], c["usedBps"], ",".join(map(str, c["thresholdsCrossed"])))
' "$bid" 2>/dev/null)
  [[ $spent == 180000* ]] && break
  sleep 1
done
if [[ $spent == "180000 9000 50,80" ]]; then printf 'PASS  %-10s %s\n' p13-spend "$spent"
else printf 'FAIL  %-10s %s\n' p13-spend "want '180000 9000 50,80' got '$spent'"; fail=1; fi
pl=$(curl -s --max-time 10 -H "Authorization: Bearer $at" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"kind":"loan","lender":"Trail bank","principalPaise":10000000,"aprBps":1200,"tenureMonths":12,"startDate":"2026-11-01"}' \
  "$BASE/api/v1/liabilities")
lid=$(jget id <<< "$pl")
lemi=$(jget emiPaise totalInterestPaise totalPayablePaise <<< "$pl")
if [[ $lemi == "888488 661853 10661853" ]]; then printf 'PASS  %-10s %s\n' p13-emi 888488
else printf 'FAIL  %-10s %s\n' p13-emi "${pl:0:200}"; fail=1; fi
# the schedule is where those stored totals came from: 12 rows summing back to the principal, closing at zero
ps=$(curl -s --max-time 10 -H "Authorization: Bearer $at" "$BASE/api/v1/liabilities/$lid/schedule?limit=24")
psum=$(printf '%s' "$ps" | python3 -c '
import json, sys
rows = json.load(sys.stdin)["data"]
print(len(rows), sum(i["principalPaise"] for i in rows), sum(i["interestPaise"] for i in rows),
      rows[-1]["outstandingAfterPaise"])
' 2>/dev/null)
if [[ $psum == "12 10000000 661853 0" ]]; then printf 'PASS  %-10s %s\n' p13-schedule "$psum"
else printf 'FAIL  %-10s %s\n' p13-schedule "${ps:0:200}"; fail=1; fi
pc=$(curl -s --max-time 10 -H "Authorization: Bearer $at" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
  -d '{"name":"trail-check","monthlyIncomePaise":10000000,"adjustments":[]}' "$BASE/api/v1/scenarios")
sid=$(jget id <<< "$pc")
curl -s -o /dev/null --max-time 10 -X PATCH -H "Authorization: Bearer $at" -H 'Content-Type: application/json' \
  -H 'If-Match: "0"' \
  -d '{"adjustments":[{"kind":"addLiability","lender":"Car loan","principalPaise":50000000,"aprBps":1000,"tenureMonths":60}]}' \
  "$BASE/api/v1/scenarios/$sid"
pe=$(curl -s --max-time 10 -X POST -H "Authorization: Bearer $at" "$BASE/api/v1/scenarios/$sid:evaluate")
# the delta is the shared engine's instalment; the surplus is checked as an invariant, not a magnitude, so other
# budgets this account happens to have (api-smoke runs first against the same stack) cannot make it flap
pev=$(printf '%s' "$pe" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print(d["deltaPaise"], d["monthlyIncomePaise"],
      d["surplusAfterPaise"] == d["monthlyIncomePaise"] - d["monthlyOutgoAfterPaise"])
' 2>/dev/null)
if [[ $pev == "1062352 10000000 True" ]]; then printf 'PASS  %-10s %s\n' p13-scenario 1062352
else printf 'FAIL  %-10s %s\n' p13-scenario "${pe:0:200}"; fail=1; fi

# P14: what p14-reco.html Live mode drives. The page's claim is that an expense recorded in the LEDGER moves the
# ranking, so this walks the whole path - expense, outbox, relay, Kafka, the spend-profile consumer, the sweeper -
# and then checks that the explanation's arithmetic adds up rather than just that it answered.
rc=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE/api/v1/recommendations")
if [[ $rc == 401 ]]; then printf 'PASS  %-10s %s\n' p14-anon 401
else printf 'FAIL  %-10s %s\n' p14-anon "$rc"; fail=1; fi
# gifts / gifts.donations: seeded paths no other check writes to, so this run's profile is its own
qcat=gifts
qexp=gifts.donations
q0=$(curl -s --max-time 10 -H "Authorization: Bearer $at" "$BASE/api/v1/recommendations")
qbase=$(printf '%s' "$q0" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print(d["monthlySpendPaise"], len(d["recommended"]) + len(d["notRecommended"]), d["savingsWeightBps"])
' 2>/dev/null)
read -r qspend qcards qweight <<< "$qbase"
if [[ ${qcards:-0} -gt 0 && ${qweight:-0} == 6000 ]]; then
  printf 'PASS  %-10s %s\n' p14-cold "$qcards cards, 60/40"
else printf 'FAIL  %-10s %s\n' p14-cold "${q0:0:200}"; fail=1; fi
curl -s -o /dev/null --max-time 10 -H "Authorization: Bearer $at" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
  -d "{\"amountPaise\":800000,\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"merchant\":\"Trail gift shop\",\"categoryPath\":\"$qexp\",\"paymentMethod\":\"upi\"}" \
  "$BASE/api/v1/expenses"
want=$(( ${qspend:-0} + 800000 ))
qmoved=""
for _ in $(seq 1 45); do
  qmoved=$(curl -s --max-time 5 -H "Authorization: Bearer $at" "$BASE/api/v1/recommendations" | python3 -c '
import json, sys
d = json.load(sys.stdin)
print(d["monthlySpendPaise"], d["spendByRoot"].get("'"$qcat"'", 0), len(d["recommended"]))
' 2>/dev/null)
  [[ $qmoved == "$want "* ]] && break
  sleep 1
done
read -r qtotal qroot qrec <<< "$qmoved"
if [[ ${qtotal:-0} == "$want" && ${qroot:-0} -ge 800000 && ${qrec:-0} -gt 0 ]]; then
  printf 'PASS  %-10s %s\n' p14-profile "$qmoved"
else printf 'FAIL  %-10s %s\n' p14-profile "want '$want <root> >0' got '$qmoved'"; fail=1; fi
# the blend on every row, and the explanation of the top card adding up
qtop=$(curl -s --max-time 10 -H "Authorization: Bearer $at" "$BASE/api/v1/recommendations" | python3 -c '
import json, sys
d = json.load(sys.stdin)
rows = d["recommended"] + d["notRecommended"]
blend = all(c["scoreBps"] == (c["savingsBps"] * 6000 + c["fitBps"] * 4000) // 10000 for c in rows)
sorted_ok = all(d["recommended"][i - 1]["scoreBps"] >= d["recommended"][i]["scoreBps"]
                for i in range(1, len(d["recommended"])))
print(d["recommended"][0]["cardProductId"] if d["recommended"] else "", blend, sorted_ok)
' 2>/dev/null)
read -r qcard qblend qsorted <<< "$qtop"
if [[ -n $qcard && $qblend == True && $qsorted == True ]]; then
  printf 'PASS  %-10s %s\n' p14-blend "60/40 on every row, sorted"
else printf 'FAIL  %-10s %s\n' p14-blend "card='$qcard' blend=$qblend sorted=$qsorted"; fail=1; fi
qx=$(curl -s --max-time 10 -H "Authorization: Bearer $at" "$BASE/api/v1/recommendations/explain/$qcard" \
  | python3 -c '
import json, sys
e = json.load(sys.stdin)
adds_up = e["annualSavingsPaise"] == (e["annualRewardPaise"] - e["fee"]["expectedFeePaise"]
                                     + e["milestone"]["annualValuePaise"])
lines_sum = sum(l["annualRewardPaise"] for l in e["lines"]) == e["annualRewardPaise"]
print(adds_up, lines_sum, 0 <= e["fee"]["waiverProbabilityBps"] <= 10000)
' 2>/dev/null)
if [[ $qx == "True True True" ]]; then printf 'PASS  %-10s %s\n' p14-explain "arithmetic adds up"
else printf 'FAIL  %-10s %s\n' p14-explain "$qx"; fail=1; fi
qs=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H "Authorization: Bearer $at" \
  "$BASE/api/v1/recommendations?cardProductIds=not-a-uuid")
if [[ $qs == 400 ]]; then printf 'PASS  %-10s %s\n' p14-badids 400
else printf 'FAIL  %-10s %s\n' p14-badids "$qs"; fail=1; fi

echo "trail-check: $([[ $fail -eq 0 ]] && echo PASS || echo FAIL)"
exit $fail
