#!/usr/bin/env bash
# P19 DLQ replay (docs/runbooks/dlq-replay.md). Moves one consumer's dead letters back onto its retry-1m topic and
# deletes them from the DLQ. Runs in.dexter.events.consumer.DlqReplay from a built service jar.
#   dlq-replay.sh <service> <topic> <consumer> [--key <message key>] [--dry-run]
#   e.g. dlq-replay.sh reco dexter.ledger.expense.v1 reco-ledger --dry-run
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
[[ $# -ge 3 ]] || { sed -n '2,5p' "$0"; exit 2; }
svc=$1 topic=$2 consumer=$3; shift 3
jar="$ROOT/services/$svc/target/app.jar"
[[ -f $jar ]] || { echo "no $jar - build first: mvn -B -q -DskipTests -pl services/$svc -am package" >&2; exit 1; }
if [[ -z ${KAFKA_BOOTSTRAP:-} && -f "$ROOT/.env" ]]; then set -a; source "$ROOT/.env"; set +a; fi
bootstrap=${KAFKA_BOOTSTRAP:-localhost:${KAFKA_PORT:-9092}}
exec java -Dloader.main=in.dexter.events.consumer.DlqReplay -cp "$jar" \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --bootstrap "$bootstrap" --topic "$topic" --consumer "$consumer" "$@"
