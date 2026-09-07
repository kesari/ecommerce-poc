#!/bin/bash
# Run a record directly through the Codex CLI: full shell, full reasoning, no
# pi SDK, no restricted toolset.
#
# A direct run costs 56-68k tokens against the harness's 260,854 and finds the
# Bruno scenarios no harnessed run found.
#
# NOT because the harness's tools are weaker. That was the first conclusion and
# it is wrong: one call to the harness's own grep, rooted at the estate with
# limit=500, returns all 14 ground-truth items for REST-001 — everything
# ripgrep returned. The harnessed agent simply never issues that call, making
# eleven narrow greps and twenty-three reads instead. The difference is which
# query the agent chooses, not which queries it can make.
#
# Two harness properties are kept, because without them a score means nothing:
#
#   Blindness. The answer key sits at ecommerce-poc/impact-study/harness/records,
#   which is INSIDE the estate. A run started there can simply read it. So we
#   copy the ten repositories to a scratch directory and leave ecommerce-poc out.
#   The copy is also why precision stays meaningful: ecommerce-poc mirrors every
#   service, and a run that can see it reports the mirror as affected.
#
#   A scoreable answer. The model is asked for the same JSON the harness scorer
#   expects, so score.py grades this exactly as it grades a harnessed run.
#
# Usage: ./direct.sh REST-001 [runs]
set -euo pipefail

RECORD="${1:?usage: direct.sh <RECORD-ID> [runs]}"
RUNS="${2:-1}"
HERE="$(cd "$(dirname "$0")" && pwd -P)"
HARNESS="$(cd "$HERE/.." && pwd -P)"
ESTATE="${POC_ESTATE:?set POC_ESTATE to the directory holding the ten repositories}"
REPOS=(account-service basket-service catalog-service commerce-bff commerce-platform
       commerce-web inventory-service order-service payment-service shipment-service)

RECORD_FILE="$HARNESS/records/$RECORD.json"
[[ -f "$RECORD_FILE" ]] || { echo "no such record: $RECORD_FILE" >&2; exit 1; }

QUERY=$(python3 -c "import json;print(json.load(open('$RECORD_FILE'))['query'])")
REPO=$(python3 -c "import json;print(json.load(open('$RECORD_FILE'))['proposed_change']['repo'])")
DIFF=$(python3 -c "import json;print(json.load(open('$RECORD_FILE'))['proposed_change']['diff'])")
# The scorer matches on canonical identifier forms. Omitting this section cost
# a direct run every contract and every test it correctly found: it reported
# "order.confirmed.v1 address.postalCode" and "01-signup-to-confirmed-order /
# create address", which are right and unmatchable. Take the convention from
# the harness template so both runners are held to one naming standard.
AGENT=$(cat "$HERE/agents/impact-analyst.md")
# Stamp which agent version produced each run. Without it, batches are only
# separable by file mtime, which fails the moment two people run concurrently
# — and it already did: a v6 window here silently mixed two people's batches.
# The pi-SDK harness solved this with prompt_sha256 in its cohort key.
AGENT_SHA=$(shasum -a 256 "$HERE/agents/impact-analyst.md" | cut -d" " -f1)

for run in $(seq 1 "$RUNS"); do
  SCRATCH=$(mktemp -d "${TMPDIR:-/tmp}/direct-$RECORD-XXXXXX")
  trap 'rm -rf "$SCRATCH"' EXIT
  WORK="$SCRATCH/estate"
  mkdir -p "$WORK"
  for name in "${REPOS[@]}"; do
    rsync -a --exclude='.git' --exclude='target' --exclude='node_modules' \
             --exclude='dist' --exclude='build' --exclude='.gradle' --exclude='.next' \
             "$ESTATE/$name" "$WORK/"
  done

  # Self-enforcing blindness, same check the harness makes.
  if find "$WORK" -path '*/records/*.json' -o -name 'REST-*.json' | grep -q .; then
    echo "ground-truth leak in the isolated estate; refusing to run" >&2
    exit 1
  fi

  STARTED=$(date -u +%Y-%m-%dT%H-%M-%S)
  OUT="$HARNESS/answers/runs/$RECORD-codex-direct-$run.$STARTED.json"
  RAW="${OUT%.json}.raw.txt"

  PROMPT=$(cat <<EOF
$QUERY

Proposed change, in repository \`$REPO\`:

\`\`\`diff
$DIFF
\`\`\`

$AGENT
EOF
)

  echo "running $RECORD codex-direct $run ..."
  LAST="$SCRATCH/last.json"
  ( cd "$WORK" && echo "" | codex exec --skip-git-repo-check \
      --model "${DIRECT_MODEL:-gpt-5.6-terra}" \
      --output-schema "$HERE/direct-answer.schema.json" \
      --output-last-message "$LAST" "$PROMPT" ) > "$RAW" 2>&1 || true

  python3 - "$LAST" "$RAW" "$OUT" "$RECORD" "$AGENT_SHA" <<'PY'
import json,re,sys,os
last,raw,out,record,agent_sha=sys.argv[1:6]
# --output-schema constrains the final message, so it is the answer verbatim.
# Never parse $RAW for it: codex echoes the prompt back, and an earlier version
# of this script scored that echoed template instead of the answer, giving a
# composite of 0.04 that looked like a catastrophic model failure.
if not os.path.exists(last):
    print("  FAILED: codex wrote no final message"); sys.exit(0)
try: answer=json.loads(open(last).read())
except Exception as exc:
    print("  FAILED: final message is not JSON:",exc); sys.exit(0)
if "findings" not in answer:
    print("  FAILED: final message has no findings key"); sys.exit(0)
tok=re.findall(r"tokens used\s*\n\s*([\d,]+)",open(raw).read())
answer["change_id"]=record
answer["contestant"]="codex-direct"
answer["runner"]="codex-direct"
answer["agent_sha256"]=agent_sha
answer["tokens_consumed"]=int(tok[-1].replace(",","")) if tok else None
json.dump(answer,open(out,"w"),indent=1)
n=sum(len(v) for v in answer["findings"].values())
print("  wrote %s | %d findings | tokens %s | agent %s"%(os.path.basename(out),n,answer["tokens_consumed"],agent_sha[:8]))
PY

  if [[ -f "$OUT" ]]; then
    python3 "$HARNESS/scoring/score.py" score --ground-truth "$RECORD_FILE" --answer "$OUT" --json \
      > "${OUT%.json}.score.json" 2>/dev/null \
      && python3 -c "import json;d=json.load(open('${OUT%.json}.score.json'));print('  composite %.2f'%d['metrics']['composite'])" \
      || echo "  scoring failed"
  fi
  rm -rf "$SCRATCH"; trap - EXIT
done
