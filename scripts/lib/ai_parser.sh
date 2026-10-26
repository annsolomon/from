# The Python ai-parser (MASTER_PLAN P12) as a local process, like the Java services. Sourced by run.sh / verify.sh
# (needs ROOT). The venv lives in .run/venv-ai-parser and is rebuilt when a requirements file changes.
PARSER_DIR="$ROOT/ai-parser"
PARSER_VENV="$ROOT/.run/venv-ai-parser"
parser_port() { echo "${PORT_AI_PARSER:-8090}"; }

parser_venv() {
  local stamp="$PARSER_VENV/.requirements.sha" want
  want=$(cat "$PARSER_DIR/requirements.txt" "$PARSER_DIR/requirements-dev.txt" | sha256sum | cut -c1-64)
  if [[ -x $PARSER_VENV/bin/python && -f $stamp && $(cat "$stamp") == "$want" ]]; then return 0; fi
  rm -rf "$PARSER_VENV"
  if ! python3 -m venv "$PARSER_VENV" >/dev/null 2>&1; then
    # Debian/Ubuntu split venv out of python3 (GitHub runners and Codespaces images usually have it).
    if sudo -n true 2>/dev/null; then
      local v; v=$(python3 -c 'import sys; print(f"{sys.version_info[0]}.{sys.version_info[1]}")')
      sudo apt-get install -y -qq "python${v}-venv" python3-venv >/dev/null 2>&1 || true
    fi
    rm -rf "$PARSER_VENV"
    python3 -m venv "$PARSER_VENV" || { echo "python3 -m venv failed (install python3-venv)"; return 1; }
  fi
  "$PARSER_VENV/bin/python" -m pip install -q --disable-pip-version-check -r "$PARSER_DIR/requirements-dev.txt" \
    || { echo "pip install of ai-parser requirements failed"; return 1; }
  echo "$want" > "$stamp"
  echo "ai-parser venv ready ($("$PARSER_VENV/bin/python" --version 2>&1))"
}

parser_test() {
  parser_venv || return 1
  (cd "$PARSER_DIR" && "$PARSER_VENV/bin/python" -m pytest -p no:cacheprovider 2>&1 | tail -n 15; exit "${PIPESTATUS[0]}")
}

parser_health() { curl -fsS --max-time 2 "http://localhost:$(parser_port)/health" 2>/dev/null | grep -q '"status":"UP"'; }

# Starts the parser in the background (pid in .run/pids, so `run.sh stop` stops it with the services).
parser_start() {
  if parser_health; then echo "ai-parser already UP on $(parser_port)"; return 0; fi
  parser_venv || return 1
  mkdir -p "$ROOT/.run/logs" "$ROOT/.run/pids"
  (cd "$PARSER_DIR" && PORT_AI_PARSER=$(parser_port) nohup "$PARSER_VENV/bin/python" -m dexter_parser \
    > "$ROOT/.run/logs/ai-parser.log" 2>&1 & echo $! > "$ROOT/.run/pids/ai-parser.pid")
  local deadline=$((SECONDS + 60))
  until parser_health; do
    (( SECONDS > deadline )) && { echo "ai-parser not UP after 60s (log: .run/logs/ai-parser.log)"; tail -n 20 "$ROOT/.run/logs/ai-parser.log"; return 1; }
    sleep 1
  done
  echo "ai-parser UP on $(parser_port)"
}
