#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
REPOWISE_BIN="${REPOWISE_BIN:-$SCRIPT_DIR/tools/repowise-venv/bin/repowise}"
PORT="${REPOWISE_PORT:-7337}"
cd "$SCRIPT_DIR/repowise/workspace"
REPOWISE_EMBEDDER=mock exec "$REPOWISE_BIN" serve --host 127.0.0.1 --port "$PORT" --no-ui
