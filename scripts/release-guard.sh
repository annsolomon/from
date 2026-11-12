#!/usr/bin/env bash
# P19 step 7 release guard (docs/release/checklist.md): a release branch carries no trail and no dev-only code.
# Lists every trail / dev item it finds. On a release branch (release/*, or RELEASE_GUARD=1) any item fails.
# Elsewhere (main keeps the trail for development) it must FIND items - proof the guard still sees them.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
branch=${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:-$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)}}
release=0; [[ $branch == release/* || ${RELEASE_GUARD:-0} == 1 ]] && release=1
items=()
add() { items+=("$1"); }
[[ -d test-ui ]] && add "test-ui/ exists"
[[ -f scripts/trail-check.sh ]] && add "scripts/trail-check.sh exists"
grep -qE 'profiles: \[trail\]' infra/compose/docker-compose.yml && add "compose has a trail profile"
ls infra/nginx/*.conf >/dev/null 2>&1 && add "trail nginx config in infra/nginx/"
grep -q 'trail-check' .github/workflows/verify-full.yml && add "verify-full.yml runs trail-check"
while IFS= read -r f; do add "dev-profile bean: $f"; done < <(grep -rlE '@Profile\("dev"\)' --include=*.java services/*/src/main libs/*/src/main 2>/dev/null)
while IFS= read -r f; do add "dev endpoint mapping: $f"; done < <(grep -rlE 'Mapping\("/_dev' --include=*.java services/*/src/main libs/*/src/main 2>/dev/null)
grep -q 'dev-routes: true' services/gateway/src/main/resources/application.yml && add "gateway dev-routes: true"
grep -qE 'allowed-origins:.*(localhost|127\.0\.0\.1)' services/gateway/src/main/resources/application.yml \
  && add "gateway dev CORS origins"
[[ -d libs/dexter-lab ]] && add "libs/dexter-lab exists"
for i in "${items[@]}"; do echo "  - $i"; done
if [[ $release -eq 1 ]]; then
  [[ ${#items[@]} -eq 0 ]] && { echo "release guard ($branch): clean"; exit 0; }
  echo "release guard ($branch): ${#items[@]} trail/dev item(s) on a release branch"; exit 1
fi
[[ ${#items[@]} -gt 0 ]] && { echo "release guard ($branch, not a release branch): sees ${#items[@]} trail/dev items (expected off release)"; exit 0; }
echo "release guard ($branch): found nothing on a development branch - the guard is blind"; exit 1
