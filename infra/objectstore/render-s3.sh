#!/bin/sh
# Renders the SeaweedFS S3 identities file (JSON) on stdout. DEV ONLY. POSIX sh (runs inside the seaweedfs image).
# One identity for the services (OBJECTSTORE_ACCESS_KEY / OBJECTSTORE_SECRET_KEY from .env); anonymous requests are
# refused once any identity exists (ADR-0003, ADR-0017). Presigned URLs carry the same identity's signature.
# Inspect on the host:  set -a; . ./.env; set +a; sh infra/objectstore/render-s3.sh
set -eu
die() { echo "render-s3: $*" >&2; exit 1; }
check() {  # value label: non-empty, no JSON or shell metacharacters
  case "$1" in
    ''|*[!A-Za-z0-9_.@%+=-]*) die "$2 must be non-empty and use only [A-Za-z0-9_.@%+=-]" ;;
  esac
}
check "${OBJECTSTORE_ACCESS_KEY:-}" OBJECTSTORE_ACCESS_KEY
check "${OBJECTSTORE_SECRET_KEY:-}" OBJECTSTORE_SECRET_KEY
[ ${#OBJECTSTORE_SECRET_KEY} -ge 16 ] || die "OBJECTSTORE_SECRET_KEY must have at least 16 characters"
cat <<JSON
{"identities":[{"name":"dexter-services","credentials":[{"accessKey":"${OBJECTSTORE_ACCESS_KEY}","secretKey":"${OBJECTSTORE_SECRET_KEY}"}],"actions":["Admin","Read","List","Tagging","Write"]}]}
JSON
