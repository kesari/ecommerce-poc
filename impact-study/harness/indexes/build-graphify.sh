#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ADMIN="$SCRIPT_DIR/index_admin.py"
DEFAULT_ESTATE="$(cd "$SCRIPT_DIR/../../.." && pwd -P)/POC-order-microservices"
ESTATE="$(cd "${1:-$DEFAULT_ESTATE}" && pwd -P)"
OUT="${2:-$SCRIPT_DIR/graphify}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd -P)"
GRAPHIFY_BIN="${GRAPHIFY_BIN:-$(command -v graphify)}"
REPOS=(account-service basket-service catalog-service commerce-bff commerce-platform commerce-web inventory-service order-service payment-service shipment-service)

python3 "$ADMIN" verify-estate --estate "$ESTATE"
EXPECTED_VERSION="$(python3 "$ADMIN" pin toolchain.graphify)"
ACTUAL_VERSION="$($GRAPHIFY_BIN --version | awk '{print $2}')"
if [[ "$ACTUAL_VERSION" != "$EXPECTED_VERSION" ]]; then
  echo "Graphify $ACTUAL_VERSION != pinned $EXPECTED_VERSION" >&2
  exit 1
fi
GRAPHIFY_PYTHON="$(dirname "$(readlink "$GRAPHIFY_BIN")")/python"
if [[ ! -x "$GRAPHIFY_PYTHON" ]]; then
  GRAPHIFY_PYTHON="${GRAPHIFY_PYTHON_BIN:-python3}"
fi
GRAPHIFY_PACKAGE="$($GRAPHIFY_PYTHON -c 'import graphify; print(graphify.__path__[0])')"
ACTUAL_PACKAGE_SHA="$(python3 "$ADMIN" package-tree-sha "$GRAPHIFY_PACKAGE")"
EXPECTED_PACKAGE_SHA="$(python3 "$ADMIN" pin toolchain.graphify-package-tree-sha256)"
if [[ "$ACTUAL_PACKAGE_SHA" != "$EXPECTED_PACKAGE_SHA" ]]; then
  echo "Graphify package SHA-256 $ACTUAL_PACKAGE_SHA != pinned $EXPECTED_PACKAGE_SHA" >&2
  exit 1
fi

MIRROR="$(mktemp -d "${TMPDIR:-/tmp}/graphify-estate.XXXXXX")"
cleanup() {
  rm -rf "$MIRROR"
}
trap cleanup EXIT

GRAPH_PATHS=()
for repo in "${REPOS[@]}"; do
  mkdir -p "$MIRROR/$repo"
  commit="$(python3 "$ADMIN" pin "repositories.$repo.commit")"
  git -C "$ESTATE/$repo" archive "$commit" | tar -x -C "$MIRROR/$repo"
  "$GRAPHIFY_BIN" update "$MIRROR/$repo" --no-cluster
  GRAPH_PATHS+=("$MIRROR/$repo/graphify-out/graph.json")
done

"$GRAPHIFY_BIN" merge-graphs "${GRAPH_PATHS[@]}" --out "$OUT/merged-graph.json"
"$GRAPHIFY_BIN" diagnose multigraph --graph "$OUT/merged-graph.json" --json > "$OUT/diagnose.json"

read -r RAW_NODES RAW_EDGES QUERY_NODES QUERY_EDGES <<< "$($GRAPHIFY_PYTHON - "$OUT/merged-graph.json" <<'PY'
import json
import sys
from copy import deepcopy
from graphify.build import build_from_json

document = json.load(open(sys.argv[1]))
graph = build_from_json(deepcopy(document), directed=False)
print(len(document.get("nodes", [])), len(document.get("links", [])), graph.number_of_nodes(), graph.number_of_edges())
PY
)"

for pair in \
  "graphify-raw-nodes=$RAW_NODES" \
  "graphify-raw-edges=$RAW_EDGES" \
  "graphify-query-nodes=$QUERY_NODES" \
  "graphify-query-edges=$QUERY_EDGES"; do
  key="${pair%%=*}"
  actual="${pair#*=}"
  expected="$(python3 "$ADMIN" pin "toolchain.$key")"
  if [[ "$actual" != "$expected" ]]; then
    echo "$key $actual != pinned $expected" >&2
    exit 1
  fi
done

python3 "$ADMIN" manifest --product graphify \
  --artifact "merged_graph=$OUT/merged-graph.json" \
  --artifact "diagnostics=$OUT/diagnose.json" \
  --metadata "raw_nodes=$RAW_NODES" \
  --metadata "raw_edges=$RAW_EDGES" \
  --metadata "query_nodes=$QUERY_NODES" \
  --metadata "query_edges=$QUERY_EDGES" \
  --metadata "normalized_nodes=$((RAW_NODES - QUERY_NODES))" \
  --metadata "normalized_edges=$((RAW_EDGES - QUERY_EDGES))" \
  --output "$SCRIPT_DIR/manifests/graphify.json"
echo "done; spot-check with: $GRAPHIFY_BIN explain \"order.confirmed\" --graph $OUT/merged-graph.json"
