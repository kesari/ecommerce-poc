#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
ADMIN="$SCRIPT_DIR/index_admin.py"
DEFAULT_ESTATE="$(cd "$SCRIPT_DIR/../../.." && pwd -P)/POC-order-microservices"
ESTATE="$(cd "${1:-$DEFAULT_ESTATE}" && pwd -P)"
OUT="${2:-$SCRIPT_DIR/scip}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd -P)"
CS_BIN="${CS_BIN:-$(command -v cs)}"
SCIP_SEARCH_BIN="${SCIP_SEARCH_BIN:-$(command -v scip-search)}"
SCIP_JAVA_HOME="${SCIP_JAVA_HOME:-$HOME/.sdkman/candidates/java/21.0.10-tem}"
JAVA_BIN="$SCIP_JAVA_HOME/bin/java"
JAVA_REPOS=(account-service basket-service catalog-service commerce-bff inventory-service order-service payment-service shipment-service)

python3 "$ADMIN" verify-estate --estate "$ESTATE"

verify_hash() {
  local path="$1"
  local key="$2"
  local expected actual
  expected="$(python3 "$ADMIN" pin "toolchain.$key")"
  actual="$(shasum -a 256 "$path" | awk '{print $1}')"
  if [[ "$actual" != "$expected" ]]; then
    echo "$path SHA-256 $actual != pinned $expected" >&2
    exit 1
  fi
}

verify_hash "$CS_BIN" coursier-sha256
verify_hash "$SCIP_SEARCH_BIN" scip-search-sha256
if [[ ! -x "$JAVA_BIN" ]] || ! "$JAVA_BIN" -version 2>&1 | grep -q '21.0.10'; then
  echo "Temurin Java 21.0.10 is required at SCIP_JAVA_HOME=$SCIP_JAVA_HOME" >&2
  exit 1
fi

SCIP_JAVA_VERSION="$(python3 "$ADMIN" pin toolchain.scip-java)"
SCIP_COORDINATE="com.sourcegraph:scip-java_2.13:$SCIP_JAVA_VERSION"
CLASSPATH="$($CS_BIN fetch --classpath "$SCIP_COORDINATE")"
SCIP_JAR=""
IFS=':' read -r -a CLASSPATH_ENTRIES <<< "$CLASSPATH"
for entry in "${CLASSPATH_ENTRIES[@]}"; do
  if [[ "$(basename "$entry")" == "scip-java_2.13-$SCIP_JAVA_VERSION.jar" ]]; then
    SCIP_JAR="$entry"
    break
  fi
done
if [[ -z "$SCIP_JAR" ]]; then
  echo "could not locate the resolved scip-java application JAR" >&2
  exit 1
fi
verify_hash "$SCIP_JAR" scip-java-jar-sha256

MIRROR="$(mktemp -d "${TMPDIR:-/tmp}/scip-estate.XXXXXX")"
cleanup() {
  rm -rf "$MIRROR"
}
trap cleanup EXIT

for repo in "${JAVA_REPOS[@]}"; do
  mkdir -p "$MIRROR/$repo"
  commit="$(python3 "$ADMIN" pin "repositories.$repo.commit")"
  git -C "$ESTATE/$repo" archive "$commit" | tar -x -C "$MIRROR/$repo"
done

for repo in "${JAVA_REPOS[@]}"; do
  start="$(date +%s)"
  (cd "$MIRROR/$repo" && JAVA_HOME="$SCIP_JAVA_HOME" "$CS_BIN" launch "$SCIP_COORDINATE" -- index)
  mkdir -p "$OUT/$repo"
  mv "$MIRROR/$repo/index.scip" "$OUT/$repo/index.scip"
  echo "$repo indexed in $(( $(date +%s) - start ))s"
done

PHYSICAL="$(cd "$MIRROR" && pwd -P)"
"$SCIP_SEARCH_BIN" aggregate-index --project-root "$PHYSICAL" \
  --root account-service --index "$OUT/account-service/index.scip" \
  --root basket-service --index "$OUT/basket-service/index.scip" \
  --root catalog-service --index "$OUT/catalog-service/index.scip" \
  --root commerce-bff --index "$OUT/commerce-bff/index.scip" \
  --root inventory-service --index "$OUT/inventory-service/index.scip" \
  --root order-service --index "$OUT/order-service/index.scip" \
  --root payment-service --index "$OUT/payment-service/index.scip" \
  --root shipment-service --index "$OUT/shipment-service/index.scip" \
  --out "$OUT/estate.scip"

MANIFEST_ARGS=()
for repo in "${JAVA_REPOS[@]}"; do
  MANIFEST_ARGS+=(--artifact "$repo=$OUT/$repo/index.scip")
done
python3 "$ADMIN" manifest --product scip-java+scip-search \
  "${MANIFEST_ARGS[@]}" \
  --artifact "estate=$OUT/estate.scip" \
  --metadata 'aggregate_project_root=ephemeral pinned-revision mirror' \
  --metadata 'excluded_repositories=["commerce-web","commerce-platform"]' \
  --output "$SCRIPT_DIR/manifests/scip.json"
echo "done; verify with: $SCIP_SEARCH_BIN symbols --index $OUT/estate.scip --name AddressResponse"
