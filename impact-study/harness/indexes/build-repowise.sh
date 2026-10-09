#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ADMIN="$SCRIPT_DIR/index_admin.py"
PIN="$SCRIPT_DIR/product-pins/repowise.json"
DEFAULT_ESTATE="$(cd "$SCRIPT_DIR/../../.." && pwd -P)/POC-order-microservices"
ESTATE="$(cd "${1:-$DEFAULT_ESTATE}" && pwd -P)"
OUT="${2:-$SCRIPT_DIR/repowise}"
REPOWISE_BIN="${REPOWISE_BIN:-$SCRIPT_DIR/tools/repowise-venv/bin/repowise}"
REPOS=(account-service basket-service catalog-service commerce-bff commerce-platform commerce-web inventory-service order-service payment-service shipment-service)

python3 "$ADMIN" verify-estate --estate "$ESTATE"
EXPECTED_VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$PIN")"
"$REPOWISE_BIN" --version | grep -F "$EXPECTED_VERSION" >/dev/null
if [[ -e "$OUT" ]]; then
  echo "$OUT already exists; move it aside before rebuilding" >&2
  exit 1
fi
mkdir -p "$OUT/workspace"
started="$(date +%s)"
for repo in "${REPOS[@]}"; do
  commit="$(python3 "$ADMIN" pin "repositories.$repo.commit")"
  git clone --quiet --no-hardlinks "$ESTATE/$repo" "$OUT/workspace/$repo"
  git -C "$OUT/workspace/$repo" checkout --quiet --detach "$commit"
done
REPOWISE_SKIP_EDITOR_SETUP=1 REPOWISE_NO_SAVE_KEY=1 "$REPOWISE_BIN" init "$OUT/workspace" --all --no-prose --no-editor-setup --no-save-key --no-agents --no-codex --no-hook --yes
duration="$(( $(date +%s) - started ))"

ARTIFACTS=(--artifact "workspace_config=$OUT/workspace/.repowise-workspace.yaml")
ARTIFACTS+=(--artifact "system_graph=$OUT/workspace/.repowise-workspace/system_graph.json")
for repo in "${REPOS[@]}"; do
  ARTIFACTS+=(--artifact "$repo=$OUT/workspace/$repo/.repowise/wiki.db")
done
python3 "$ADMIN" manifest --product repowise --product-pin "$PIN" "${ARTIFACTS[@]}" \
  --metadata "duration_seconds=$duration" \
  --metadata "workspace=repowise/workspace" \
  --output "$SCRIPT_DIR/manifests/repowise.json"
echo "start the query API with: $SCRIPT_DIR/serve-repowise.sh"
