#!/bin/sh
# Renders infra/redis/acl.tmpl into a Redis ACL file on stdout. DEV ONLY. POSIX sh (runs inside redis:alpine).
#   default user : password REDIS_PASSWORD, all permissions (ops / healthcheck only; services never use it)
#   service users: DEXTER_REDIS_USERS; password REDIS_PW_<SERVICE> (upper case), else REDIS_PW_SERVICE
# Inspect on the host:  set -a; . ./.env; set +a; sh infra/redis/render-acl.sh
set -eu
die() { echo "render-acl: $*" >&2; exit 1; }
tmpl=${1:-$(dirname "$0")/acl.tmpl}
[ -r "$tmpl" ] || die "template not readable: $tmpl"
users=${DEXTER_REDIS_USERS:-}
[ -n "$users" ] || die "DEXTER_REDIS_USERS is empty (see .env.example)"
# DEV-ONLY: the P02 trail lab (dexter-lab, A-23) has its own user `lab`. Removed in P19.
case " $users " in *" lab "*) ;; *) users="$users lab" ;; esac
line=$(grep '^user {{svc}} ' "$tmpl" | head -n 1)
[ -n "$line" ] || die "no 'user {{svc}} ...' line in $tmpl"
check_pw() {  # value label: no spaces and no sed/ACL metacharacters
  case "$1" in
    ''|*[!A-Za-z0-9_.@%+=-]*) die "password for $2 must be non-empty and use only [A-Za-z0-9_.@%+=-]" ;;
  esac
}
check_pw "${REDIS_PASSWORD:-}" default
echo "user default on >${REDIS_PASSWORD} ~* &* +@all"
for svc in $users; do
  case "$svc" in ''|*[!a-z0-9_]*) die "invalid service name: $svc" ;; esac
  up=$(echo "$svc" | tr 'a-z' 'A-Z')
  eval "pw=\${REDIS_PW_${up}:-\${REDIS_PW_SERVICE:-}}"
  check_pw "$pw" "$svc"
  echo "$line" | sed -e "s/{{svc}}/${svc}/g" -e "s/{{pw}}/${pw}/g"
done
