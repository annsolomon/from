#!/usr/bin/env bash
# docker-entrypoint-initdb.d hook. Safe whether the entrypoint executes or sources it.
bash /opt/dexter/postgres/apply-roles.sh
