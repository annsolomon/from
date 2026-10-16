#!/usr/bin/env bash
# Installs tools verify.sh full needs when the devcontainer post-create did not run
# (e.g. dexter-v2 lives in a subfolder, so Codespaces used another devcontainer). Idempotent.
set -euo pipefail

install_k6() {
  if command -v k6 >/dev/null 2>&1; then echo "k6 present: $(k6 version | head -n1)"; return; fi
  local arch; case "$(uname -m)" in x86_64) arch=amd64 ;; aarch64|arm64) arch=arm64 ;; *) echo "unsupported arch"; exit 1 ;; esac
  # Resolve the latest tag from the /releases/latest redirect (no API call, so no unauthenticated rate limit).
  # Captured into a variable, never piped into an early-exiting reader (that caused curl error 23).
  local url tag
  url=$(curl -fsSL -o /dev/null -w '%{url_effective}' https://github.com/grafana/k6/releases/latest)
  tag=${url##*/}
  [[ $tag == v* ]] || tag=""
  [[ -n $tag ]] || { echo "could not resolve latest k6 release"; exit 1; }
  local tmp; tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/k6.tgz" "https://github.com/grafana/k6/releases/download/${tag}/k6-${tag}-linux-${arch}.tar.gz"
  tar -xzf "$tmp/k6.tgz" -C "$tmp"
  local dest=/usr/local/bin
  if [[ -w $dest ]]; then install -m 0755 "$tmp"/k6-*/k6 "$dest/k6"
  elif sudo -n true 2>/dev/null; then sudo install -m 0755 "$tmp"/k6-*/k6 "$dest/k6"
  else dest="$HOME/.local/bin"; mkdir -p "$dest"; install -m 0755 "$tmp"/k6-*/k6 "$dest/k6"
       [[ ":$PATH:" == *":$dest:"* ]] || echo "add to PATH: export PATH=\"$dest:\$PATH\""; fi
  rm -rf "$tmp"
  echo "k6 ${tag} installed to $dest"
}

install_k6
