#!/usr/bin/env bash
# P19 (docs/runbooks/restore.md): backup + restore drill for every service database. For each one: pg_dump -Fc
# (the backup format the runbook uses), restore into <db>_restore, then compare every table's row count and a
# content hash of the tables that matter most (expenses, account keys, the audit chain). Run with the services
# stopped so nothing moves between the dump and the comparison. Drops the restored copies afterwards.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
set -a; source "$ROOT/.env"; set +a
COMPOSE=(docker compose --env-file "$ROOT/.env" -f "$ROOT/infra/compose/docker-compose.yml")
PG=("${COMPOSE[@]}" exec -T postgres)
U=${POSTGRES_USER:-postgres}
counts_sql() { # every base table (partitions included) with its row count, in a fixed order
  cat <<SQL
SELECT table_schema || '.' || table_name || '=' ||
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', table_schema, table_name),
         false, true, '')))[1]::text
FROM information_schema.tables WHERE table_schema = '$1' AND table_type = 'BASE TABLE' ORDER BY 1;
SQL
}
hash_sql() {
  case $1 in
    ledger)   echo "SELECT md5(coalesce(string_agg(id::text || ':' || amount_paise || ':' || version, ',' ORDER BY id), '')) FROM ledger.expenses" ;;
    identity) echo "SELECT md5(coalesce(string_agg(account_id::text || ':' || encode(dek_enc, 'hex') || ':' || kek_id, ',' ORDER BY account_id), '')) FROM identity.account_keys" ;;
    audit)    echo "SELECT md5(coalesce(string_agg(seq::text || ':' || encode(hash, 'hex'), ',' ORDER BY seq), '')) FROM audit.audit_log" ;;
    *)        echo "SELECT 'n/a'" ;;
  esac
}
fail=0 total_rows=0 dbs=0
for db in $DEXTER_DB_SERVICES; do
  t0=$SECONDS
  "${PG[@]}" sh -c "pg_dump -U $U -Fc -d $db -f /tmp/$db.dump" || { echo "FAIL $db: pg_dump"; fail=1; continue; }
  size=$("${PG[@]}" sh -c "du -k /tmp/$db.dump | cut -f1")
  "${PG[@]}" psql -U "$U" -d postgres -qc "DROP DATABASE IF EXISTS ${db}_restore WITH (FORCE)" >/dev/null
  "${PG[@]}" psql -U "$U" -d postgres -qc "CREATE DATABASE ${db}_restore OWNER ${db}_owner" >/dev/null
  if ! "${PG[@]}" sh -c "pg_restore -U $U --exit-on-error -d ${db}_restore /tmp/$db.dump" > /tmp/restore-$db.log 2>&1; then
    echo "FAIL $db: pg_restore"; tail -n 5 /tmp/restore-$db.log; fail=1; continue
  fi
  a=$("${PG[@]}" psql -U "$U" -d "$db" -tAc "$(counts_sql "$db")")
  b=$("${PG[@]}" psql -U "$U" -d "${db}_restore" -tAc "$(counts_sql "$db")")
  ha=$("${PG[@]}" psql -U "$U" -d "$db" -tAc "$(hash_sql "$db")")
  hb=$("${PG[@]}" psql -U "$U" -d "${db}_restore" -tAc "$(hash_sql "$db")")
  rows=$(awk -F= '{s+=$2} END {print s+0}' <<< "$a")
  tables=$(wc -l <<< "$a")
  rls=$("${PG[@]}" psql -U "$U" -d "${db}_restore" -tAc "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '$db' AND c.relkind IN ('r','p') AND c.relrowsecurity AND c.relforcerowsecurity")
  rls0=$("${PG[@]}" psql -U "$U" -d "$db" -tAc "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '$db' AND c.relkind IN ('r','p') AND c.relrowsecurity AND c.relforcerowsecurity")
  if [[ -z $a || $a != "$b" || $ha != "$hb" || $rls != "$rls0" || $rows -eq 0 ]]; then
    echo "FAIL $db: restored copy differs (tables/rows/hash/RLS) or is empty: rows $rows, hash $ha vs $hb, RLS $rls0 vs $rls"
    diff <(echo "$a") <(echo "$b") | head -n 10
    fail=1
  else
    echo "ok   $db: ${size} KB dump, $tables tables, $rows rows identical, key hash $ha, RLS forced on $rls tables, $((SECONDS - t0))s"
    total_rows=$((total_rows + rows)); dbs=$((dbs + 1))
  fi
  "${PG[@]}" psql -U "$U" -d postgres -qc "DROP DATABASE IF EXISTS ${db}_restore WITH (FORCE)" >/dev/null
  "${PG[@]}" rm -f "/tmp/$db.dump"
done
echo "restore drill: $dbs/$(wc -w <<< "$DEXTER_DB_SERVICES") databases restored identically, $total_rows rows; $([[ $fail -eq 0 ]] && echo PASS || echo FAIL)"
exit $fail
