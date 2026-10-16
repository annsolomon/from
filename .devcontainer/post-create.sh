#!/usr/bin/env bash
# Codespaces post-create: k6, .env, tool versions. Non-fatal on k6 so the container still comes up.
set -uo pipefail
cd "$(dirname "$0")/.."

if ! command -v k6 >/dev/null 2>&1 && ! bash scripts/install-tools.sh; then
  echo "installing k6..."
  sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
    --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69 \
  && echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
     | sudo tee /etc/apt/sources.list.d/k6.list >/dev/null \
  && sudo apt-get update -qq && sudo apt-get install -y -qq k6 \
  || echo "WARN: k6 install failed; verify.sh full will FAIL the k6 step (run scripts/install-tools.sh)"
fi

[[ -f .env ]] || { cp .env.example .env; echo "created .env from .env.example"; }
chmod +x scripts/*.sh infra/postgres/*.sh infra/postgres/init/*.sh 2>/dev/null || true

echo "--- toolchain ---"
java -version 2>&1 | head -n1
mvn -v 2>/dev/null | head -n1
docker --version
k6 version 2>/dev/null | head -n1 || true
