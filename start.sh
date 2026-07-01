#!/usr/bin/env bash
# Launch Fluree + the Akka service. Idempotent — safe to re-run.
set -euo pipefail

: "${GOOGLE_AI_GEMINI_API_KEY:?Please export GOOGLE_AI_GEMINI_API_KEY before running}"

FLUREE_DIR="${FLUREE_DIR:-.fluree-runtime}"
FLUREE_BIN="${FLUREE_BIN:-fluree}"
LEDGER="${LEDGER:-memory}"

if ! command -v "$FLUREE_BIN" >/dev/null 2>&1; then
  cat <<EOF
Fluree not found on PATH. Download the binary from
  https://github.com/fluree/db/releases
and add it to PATH, or set FLUREE_BIN to its location.
EOF
  exit 1
fi

mkdir -p "$FLUREE_DIR"
cd "$FLUREE_DIR"

if [ ! -d ".fluree" ]; then
  echo "→ initializing Fluree in $FLUREE_DIR"
  "$FLUREE_BIN" init
fi

if ! "$FLUREE_BIN" server status >/dev/null 2>&1; then
  echo "→ starting Fluree server on 127.0.0.1:8090"
  "$FLUREE_BIN" server start --listen-addr 127.0.0.1:8090
  sleep 2
fi

if ! "$FLUREE_BIN" list 2>/dev/null | grep -q "^${LEDGER}$"; then
  echo "→ creating ledger '${LEDGER}'"
  "$FLUREE_BIN" create "$LEDGER"
fi

cd - >/dev/null
echo "→ starting Akka service on http://localhost:9000"
exec mvn -q compile exec:java
