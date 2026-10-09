#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ADMIN="$SCRIPT_DIR/index_admin.py"
PIN="$SCRIPT_DIR/product-pins/codebase-memory.json"
DEFAULT_ESTATE="$(cd "$SCRIPT_DIR/../../.." && pwd -P)/POC-order-microservices"
ESTATE="$(cd "${1:-$DEFAULT_ESTATE}" && pwd -P)"
OUT="${2:-$SCRIPT_DIR/codebase-memory}"
CBM_BIN="${CODEBASE_MEMORY_BIN:-$SCRIPT_DIR/tools/codebase-memory-mcp}"
REPOS=(account-service basket-service catalog-service commerce-bff commerce-platform commerce-web inventory-service order-service payment-service shipment-service)

python3 "$ADMIN" verify-estate --estate "$ESTATE"
EXPECTED_VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$PIN")"
EXPECTED_SHA="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["binary_sha256"])' "$PIN")"
echo "$EXPECTED_SHA  $CBM_BIN" | shasum -a 256 -c -
"$CBM_BIN" --version | grep -F "$EXPECTED_VERSION" >/dev/null
if [[ -e "$OUT" ]]; then
  echo "$OUT already exists; move it aside before rebuilding" >&2
  exit 1
fi
mkdir -p "$OUT/cache"
started="$(date +%s)"
for repo in "${REPOS[@]}"; do
  CBM_CACHE_DIR="$OUT/cache" CBM_ALLOWED_ROOT="$ESTATE" "$CBM_BIN" cli --quiet index_repository --repo-path "$ESTATE/$repo" --name "$repo" --mode full >/dev/null
done
for repo in "${REPOS[@]}"; do
  CBM_CACHE_DIR="$OUT/cache" CBM_ALLOWED_ROOT="$ESTATE" "$CBM_BIN" cli --quiet index_repository --repo-path "$ESTATE/$repo" --name "$repo" --mode cross-repo-intelligence --target-projects '*' >/dev/null
done
duration="$(( $(date +%s) - started ))"

ARTIFACTS=()
index=0
while IFS= read -r database; do
  index="$((index + 1))"
  ARTIFACTS+=(--artifact "store_$index=$database")
done < <(find "$OUT/cache" -type f -name '*.db' -print | LC_ALL=C sort)
if [[ "${#ARTIFACTS[@]}" -eq 0 ]]; then
  echo "Codebase Memory created no database artifacts" >&2
  exit 1
fi
python3 "$ADMIN" manifest --product codebase-memory-mcp --product-pin "$PIN" "${ARTIFACTS[@]}" \
  --metadata "duration_seconds=$duration" \
  --metadata "cache=codebase-memory/cache" \
  --output "$SCRIPT_DIR/manifests/codebase-memory.json"
CBM_CACHE_DIR="$OUT/cache" "$CBM_BIN" cli --quiet list_projects --format json --detail stats
