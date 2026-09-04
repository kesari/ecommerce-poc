#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ADMIN="$SCRIPT_DIR/index_admin.py"
DEFAULT_ESTATE="$(cd "$SCRIPT_DIR/../../.." && pwd -P)/POC-order-microservices"
ESTATE="$(cd "${1:-$DEFAULT_ESTATE}" && pwd -P)"
GORTEX_BIN="${GORTEX_BIN:-$(command -v gortex)}"
WORKSPACE="$(python3 "$ADMIN" pin toolchain.gortex-workspace)"

python3 "$ADMIN" verify-estate --estate "$ESTATE"
if ! "$GORTEX_BIN" daemon status >/dev/null 2>&1; then
  "$GORTEX_BIN" daemon start --detach --tools readonly
fi

for repo in account-service basket-service catalog-service commerce-bff commerce-platform commerce-web inventory-service order-service payment-service shipment-service; do
  "$GORTEX_BIN" track "$ESTATE/$repo"
  "$GORTEX_BIN" workspace set "$repo" "$WORKSPACE" --global
done
"$GORTEX_BIN" daemon reload
for repo in account-service basket-service catalog-service commerce-bff commerce-platform commerce-web inventory-service order-service payment-service shipment-service; do
  "$GORTEX_BIN" track "$ESTATE/$repo" --wait --wait-timeout 10m
done

python3 "$ADMIN" verify-gortex --estate "$ESTATE" --binary "$GORTEX_BIN" --require-daemon
python3 "$ADMIN" manifest --product gortex \
  --artifact "binary=$GORTEX_BIN" \
  --metadata "workspace=$WORKSPACE" \
  --metadata 'build_features=["no-llama","no-embeddings"]' \
  --metadata 'repository_artifacts=false' \
  --output "$SCRIPT_DIR/manifests/gortex.json"
