#!/usr/bin/env bash
# Local runner for Codespaces.
#   run.sh core|obs|all   start infra profile(s) and wait until healthy
#   run.sh services             build (if needed) and start all 10 services + the ai-parser in the background
#   run.sh parser               start only the ai-parser (Python venv in .run/venv-ai-parser)
#   run.sh svc <name>           run one service in the foreground (mvn spring-boot:run)
#   run.sh stop                 stop background services
#   run.sh down                 stop all containers (keeps volumes)
#   run.sh nuke                 stop all containers AND delete volumes (fresh databases)
#   run.sh status               containers + service health + obs probes
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
source scripts/lib/services.sh
source scripts/lib/ai_parser.sh
RUN_DIR="$ROOT/.run"
COMPOSE=(docker compose --env-file "$ROOT/.env" -f "$ROOT/infra/compose/docker-compose.yml")
ALL_PROFILES=(--profile core --profile obs)

[[ -f .env ]] || { echo "no .env - run: cp .env.example .env" >&2; exit 1; }
# Keys that later phases add to .env.example (P04: IDENTITY_*, SERVICE_CLIENT_SECRET) are appended to an existing
# .env; values already in .env are never changed.
while IFS= read -r line; do
  [[ $line =~ ^([A-Z][A-Z0-9_]*)= ]] || continue
  grep -q "^${BASH_REMATCH[1]}=" .env || { printf '%s\n' "$line" >> .env; echo "run.sh: added ${BASH_REMATCH[1]} to .env"; }
done < .env.example
set -a; source .env; set +a

health() { curl -fsS --max-time 2 "http://localhost:$1/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; }

start_services() {
  local missing=0 entry svc port
  # Rebuild when a jar is missing OR any source/pom is newer than it (P01 fix: after `unzip -o` the
  # old jars from target/ would otherwise be started with the new code never compiled).
  for entry in "${DEXTER_SERVICES[@]}"; do
    local jar="services/${entry%%:*}/target/app.jar"
    if [[ ! -f $jar ]] || [[ -n $(find libs services pom.xml platform-bom -newer "$jar" \( -name '*.java' -o -name '*.xml' -o -name '*.yml' -o -name '*.imports' -o -name '*.sql' \) -not -path '*/target/*' -print -quit 2>/dev/null) ]]; then
      missing=1; break
    fi
  done
  if [[ $missing -eq 1 ]]; then echo "building service jars (missing or stale)..."; mvn -B -q -DskipTests package; fi
  # Local runs use the dev profile (relaxed local settings: plain-HTTP headers, full activity sampling). Never in prod.
  export SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-dev}
  echo "SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE"
  # P18 (ADR-0024): the running stack takes part in the privacy saga. Set here, not in .env: verify.sh exports .env
  # into the Maven ITs, whose service contexts must never start a privacy consumer.
  export DEXTER_PRIVACY_ENABLED=${DEXTER_PRIVACY_ENABLED:-true}
  # P19 (ADR-0025): spans go to the obs profile's collector (127.0.0.1:4318). Exporting with no collector running
  # only logs a warning. Not in .env, for the same reason as above: the Maven ITs must not export.
  export DEXTER_TRACING_ENABLED=${DEXTER_TRACING_ENABLED:-true}
  # P19 (ADR-0026): the KEK re-wrap job every 30 s locally (10 min default), so keys the seed wrapped under k1 move to
  # Transit within a smoke run.
  export DEXTER_IDENTITY_KEK_REWRAP_EVERY=${DEXTER_IDENTITY_KEK_REWRAP_EVERY:-30s}
  mkdir -p "$RUN_DIR/logs" "$RUN_DIR/pids"
  # P19 (ADR-0026): with Vault up, each service starts with only its own secrets, read from Vault with its own
  # scoped token (vault_tool.py env); every other secret in .env is removed from its environment. DEXTER_VAULT=off
  # starts them the old way (.env for everyone).
  local vault=off
  if [[ ${DEXTER_VAULT:-auto} != off && -f $RUN_DIR/vault/identity.token ]] \
      && curl -fsS --max-time 2 "http://127.0.0.1:${VAULT_PORT:-8200}/v1/sys/health" >/dev/null 2>&1; then
    vault=on
  fi
  echo "secrets: $([[ $vault == on ]] && echo 'from Vault (per-service tokens)' || echo 'from .env (Vault not used)')"
  for entry in "${DEXTER_SERVICES[@]}"; do
    svc=${entry%%:*}; port=${entry##*:}
    if health "$port"; then echo "$svc already UP on $port"; continue; fi
    local venv=()
    if [[ $vault == on ]]; then
      mapfile -d '' venv < <(python3 scripts/lib/vault_tool.py env "$svc")
      [[ ${#venv[@]} -gt 0 ]] || { echo "vault: no environment for $svc - not started"; continue; }
    fi
    # DEXTER_JAVA_OPTS: small heaps and C1 only for a fast local start; the scale workflow gives full JIT + heap.
    # shellcheck disable=SC2086
    nohup env "${venv[@]}" java ${DEXTER_JAVA_OPTS:--Xms64m -Xmx256m -XX:TieredStopAtLevel=1} -jar "services/$svc/target/app.jar" \
      > "$RUN_DIR/logs/$svc.log" 2>&1 &
    echo $! > "$RUN_DIR/pids/$svc.pid"
  done
  local deadline=$((SECONDS + 180)) down
  while :; do
    down=()
    for entry in "${DEXTER_SERVICES[@]}"; do health "${entry##*:}" || down+=("${entry%%:*}"); done
    if [[ ${#down[@]} -eq 0 ]]; then echo "all ${#DEXTER_SERVICES[@]} services UP"; parser_start; return $?; fi
    (( SECONDS > deadline )) && { echo "not UP after 180s: ${down[*]} (logs in .run/logs)"; return 1; }
    sleep 3
  done
}

stop_services() {
  local f
  shopt -s nullglob
  for f in "$RUN_DIR"/pids/*.pid; do kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"; done
  echo "services stopped"
}

status() {
  "${COMPOSE[@]}" "${ALL_PROFILES[@]}" ps
  local entry
  for entry in "${DEXTER_SERVICES[@]}"; do
    if health "${entry##*:}"; then echo "UP    ${entry%%:*} :${entry##*:}"; else echo "DOWN  ${entry%%:*} :${entry##*:}"; fi
  done
  if parser_health; then echo "UP    ai-parser :$(parser_port)"; else echo "DOWN  ai-parser :$(parser_port)"; fi
  curl -fsS --max-time 2 http://localhost:13133/ >/dev/null 2>&1 && echo "UP    otel-collector" || echo "DOWN  otel-collector (obs profile)"
  curl -fsS --max-time 2 http://localhost:3200/ready >/dev/null 2>&1 && echo "UP    tempo" || echo "DOWN  tempo (obs profile)"
}

cmd=${1:-help}
case "$cmd" in
  core)           "${COMPOSE[@]}" --profile core up -d --wait --wait-timeout 300 --remove-orphans
                  # P19 (ADR-0026): initialise / unseal Vault and (re)write the per-service secrets, policies, tokens.
                  python3 scripts/lib/vault_tool.py init ;;
  obs)            "${COMPOSE[@]}" --profile obs up -d --wait --wait-timeout 300 --remove-orphans ;;
  all)            "${COMPOSE[@]}" "${ALL_PROFILES[@]}" up -d --wait --wait-timeout 300 --remove-orphans
                  python3 scripts/lib/vault_tool.py init ;;
  services)       start_services ;;
  parser)         parser_start ;;
  svc)            [[ -n ${2:-} ]] || { echo "usage: run.sh svc <name>"; exit 2; }
                  mvn -B -q -pl "services/$2" -am -DskipTests install && mvn -B -pl "services/$2" spring-boot:run ;;
  stop)           stop_services ;;
  down)           stop_services; "${COMPOSE[@]}" "${ALL_PROFILES[@]}" down --remove-orphans ;;
  nuke)           stop_services; "${COMPOSE[@]}" "${ALL_PROFILES[@]}" down -v --remove-orphans; rm -rf "$RUN_DIR/vault" ;;
  status)         status ;;
  *)              sed -n '2,12p' "$0"; exit 2 ;;
esac
