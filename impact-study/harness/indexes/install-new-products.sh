#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
TOOLS="$SCRIPT_DIR/tools"
PIN_DIR="$SCRIPT_DIR/product-pins"
mkdir -p "$TOOLS"

if [[ "$(uname -s)-$(uname -m)" != "Darwin-arm64" ]]; then
  echo "the committed distribution pins currently cover macOS arm64 only" >&2
  exit 1
fi

REP_VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$PIN_DIR/repowise.json")"
REP_WHEEL="repowise-$REP_VERSION-py3-none-any.whl"
REP_SHA="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["distribution"]["sha256"])' "$PIN_DIR/repowise.json")"
REP_URL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["distribution"]["url"])' "$PIN_DIR/repowise.json")"
REP_PYTHON="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["python"])' "$PIN_DIR/repowise.json")"
REP_LOCK="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["dependency_lock"]["name"])' "$PIN_DIR/repowise.json")"
REP_LOCK_SHA="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["dependency_lock"]["sha256"])' "$PIN_DIR/repowise.json")"
if [[ ! -f "$TOOLS/$REP_WHEEL" ]]; then
  curl -fL --connect-timeout 15 --max-time 300 --retry 3 "$REP_URL" -o "$TOOLS/$REP_WHEEL.part"
  mv "$TOOLS/$REP_WHEEL.part" "$TOOLS/$REP_WHEEL"
fi
echo "$REP_SHA  $TOOLS/$REP_WHEEL" | shasum -a 256 -c -
echo "$REP_LOCK_SHA  $PIN_DIR/$REP_LOCK" | shasum -a 256 -c -
if [[ ! -x "$TOOLS/repowise-venv/bin/repowise" ]]; then
  UV_CACHE_DIR="$TOOLS/uv-cache" UV_PYTHON_INSTALL_DIR="$TOOLS/python" uv venv --python "$REP_PYTHON" "$TOOLS/repowise-venv"
  UV_CACHE_DIR="$TOOLS/uv-cache" UV_PYTHON_INSTALL_DIR="$TOOLS/python" uv pip install --python "$TOOLS/repowise-venv/bin/python" -r "$PIN_DIR/$REP_LOCK"
  UV_CACHE_DIR="$TOOLS/uv-cache" UV_PYTHON_INSTALL_DIR="$TOOLS/python" uv pip install --python "$TOOLS/repowise-venv/bin/python" --no-deps "$TOOLS/$REP_WHEEL"
fi
diff -u "$PIN_DIR/$REP_LOCK" <(UV_CACHE_DIR="$TOOLS/uv-cache" UV_PYTHON_INSTALL_DIR="$TOOLS/python" uv pip freeze --python "$TOOLS/repowise-venv/bin/python" | grep -v '^repowise')
"$TOOLS/repowise-venv/bin/repowise" --version

CBM_VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$PIN_DIR/codebase-memory.json")"
CBM_ARCHIVE="codebase-memory-mcp-darwin-arm64.tar.gz"
CBM_ARCHIVE_SHA="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["distribution"]["sha256"])' "$PIN_DIR/codebase-memory.json")"
CBM_BINARY_SHA="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["binary_sha256"])' "$PIN_DIR/codebase-memory.json")"
if [[ ! -f "$TOOLS/$CBM_ARCHIVE" ]]; then
  curl -fL --connect-timeout 15 --max-time 600 --retry 3 "https://github.com/DeusData/codebase-memory-mcp/releases/download/v$CBM_VERSION/$CBM_ARCHIVE" -o "$TOOLS/$CBM_ARCHIVE.part"
  mv "$TOOLS/$CBM_ARCHIVE.part" "$TOOLS/$CBM_ARCHIVE"
fi
echo "$CBM_ARCHIVE_SHA  $TOOLS/$CBM_ARCHIVE" | shasum -a 256 -c -
if [[ ! -x "$TOOLS/codebase-memory-mcp" ]]; then
  tar -xzf "$TOOLS/$CBM_ARCHIVE" -C "$TOOLS"
fi
echo "$CBM_BINARY_SHA  $TOOLS/codebase-memory-mcp" | shasum -a 256 -c -
"$TOOLS/codebase-memory-mcp" --version
