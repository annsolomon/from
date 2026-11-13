#!/usr/bin/env bash
# Proves the frontend's calculators are the backend's: compiles libs/portfolio-calc (plain javac, no Maven) plus
# RefGrid.java, prints 1,276 reference answers from the REAL Java engines, and checks the JS ports against them to the
# paisa. Then runs every endpoint against the in-browser mock backend in node. Needs: JDK 21+, node 18+.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd); OUT=$(mktemp -d)
javac -d "$OUT" $(find "$ROOT/libs/portfolio-calc/src/main/java" -name '*.java') "$HERE/RefGrid.java"
java -cp "$OUT" in.dexter.portfolio.calc.RefGrid > "$OUT/ref.json"
node "$HERE/verify-calc.js" "$OUT/ref.json"
node "$HERE/mock-runall.js"
