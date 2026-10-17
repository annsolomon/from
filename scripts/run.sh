#!/usr/bin/env bash
# Local runner for Codespaces.
#   run.sh core|obs|trail|all   start infra profile(s) and wait until healthy
#   run.sh services             build (if needed) and start all 10 services in the background
#   run.sh svc <name>           run one service in the foreground (mvn spring-boot:run)
#   run.sh stop                 stop background services
#   run.sh down                 stop all containers (keeps volumes)
#   run.sh nuke                 stop all containers AND delete volumes (fresh databases)
#   run.sh status               containers + service health + obs probes
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
source scripts/lib/services.sh
RUN_DIR="$ROOT/.run"
COMPOSE=(docker compose --env-file "$ROOT/.env" -f "$ROOT/infra/compose/docker-compose.yml")
ALL_PROFILES=(--profile core --profile obs --profile trail)

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
  # Local runs use the dev profile (dev-only endpoints such as /_dev/p01 for the trail). Never in prod.
  export SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-dev}
  echo "SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE"
  mkdir -p "$RUN_DIR/logs" "$RUN_DIR/pids"
  for entry in "${DEXTER_SERVICES[@]}"; do
    svc=${entry%%:*}; port=${entry##*:}
    if health "$port"; then echo "$svc already UP on $port"; continue; fi
    nohup java -Xms64m -Xmx256m -XX:TieredStopAtLevel=1 -jar "services/$svc/target/app.jar" \
      > "$RUN_DIR/logs/$svc.log" 2>&1 &
    echo $! > "$RUN_DIR/pids/$svc.pid"
  done
  local deadline=$((SECONDS + 180)) down
  while :; do
    down=()
    for entry in "${DEXTER_SERVICES[@]}"; do health "${entry##*:}" || down+=("${entry%%:*}"); done
    [[ ${#down[@]} -eq 0 ]] && { echo "all ${#DEXTER_SERVICES[@]} services UP"; return 0; }
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
  curl -fsS --max-time 2 http://localhost:13133/ >/dev/null 2>&1 && echo "UP    otel-collector" || echo "DOWN  otel-collector (obs profile)"
  curl -fsS --max-time 2 http://localhost:3200/ready >/dev/null 2>&1 && echo "UP    tempo" || echo "DOWN  tempo (obs profile)"
}

cmd=${1:-help}
case "$cmd" in
  core|obs)       "${COMPOSE[@]}" --profile "$cmd" up -d --wait --wait-timeout 300 --remove-orphans ;;
  trail)          # stateless: always recreate so nginx picks up the current trail.conf
                  "${COMPOSE[@]}" --profile trail up -d --wait --wait-timeout 120 --force-recreate trail ;;
  all)            "${COMPOSE[@]}" "${ALL_PROFILES[@]}" up -d --wait --wait-timeout 300 --remove-orphans ;;
  services)       start_services ;;
  svc)            [[ -n ${2:-} ]] || { echo "usage: run.sh svc <name>"; exit 2; }
                  mvn -B -q -pl "services/$2" -am -DskipTests install && mvn -B -pl "services/$2" spring-boot:run ;;
  stop)           stop_services ;;
  down)           stop_services; "${COMPOSE[@]}" "${ALL_PROFILES[@]}" down --remove-orphans ;;
  nuke)           stop_services; "${COMPOSE[@]}" "${ALL_PROFILES[@]}" down -v --remove-orphans ;;
  status)         status ;;
  *)              sed -n '2,11p' "$0"; exit 2 ;;
esac
