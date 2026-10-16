#!/usr/bin/env bash
# Runs inside the postgres container during first init (called by init/10-apply-roles.sh).
set -euo pipefail
# DEV-ONLY: the P02 trail lab (dexter-lab, A-23) gets its own database + roles `lab_*`. Removed in P19.
case " $DEXTER_DB_SERVICES " in *" lab "*) ;; *) export DEXTER_DB_SERVICES="$DEXTER_DB_SERVICES lab" ;; esac
bash /opt/dexter/postgres/render-roles.sh /opt/dexter/postgres/init/00-roles.sql.tmpl \
  | psql -v ON_ERROR_STOP=1 --no-psqlrc --username "$POSTGRES_USER" --dbname postgres
echo "dexter: roles + databases created for: $DEXTER_DB_SERVICES"
