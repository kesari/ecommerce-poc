# Ground-Truth Harness

Frozen ground truth plus deterministic scoring for the cross-repo
change-impact POC. Write the ground truth for a change first, then let each
contestant answer the same question blind.

```text
records/   ground truth, one JSON file per change
answers/   runs/ real output · examples/ synthetic · rejected/ invalid
schema/    JSON Schema for records and answers
run/       run.ts       the pipeline, one stage per module:
           estate.ts      isolated, fingerprinted copy of the estate + guarded tools
           answer.ts      pull the answer object out of the model's prose
           scoring.ts     hand it to the Python scorer
           aggregate.py   corpus scorecard across runs
scoring/   score.py   scoring, schema validation, and the readable report
```

Protocol 1 is historical. Protocol 2 adds independently pinned executable
evidence and scores downstream edits separately from runtime impact.
REST-001, INT-002 and KAFKA-001 have verified scoped records and pinned oracle
bundles. See the
[implementation status](../docs/methodology/implementation-status-v2.md).
For corrected REST repair matching, use the
[revision 2.1 protocol](../docs/methodology/2026-10-18-scorer-v2.1-repair.md) and
its separately pinned oracle. The original scorer/score replay is preserved in
`history/2026-10-18-original-v2/`.

One run is: isolate the estate → restrict the tools → prompt the model →
extract the answer → stamp provenance → validate → score. A run that fails a
stage is reported and skipped; the others continue.

## Run

Node 22.19+ for the runner, Python 3 stdlib for scoring. No other dependencies.

```bash
cd run
npm ci --ignore-scripts             # once
node run.ts --record REST-001 --runs 1
node run.ts --record all --contestant pi-qwen3-coder --runs 3
node run.ts --record all --runs 3   # every contestant in contestants.json
python3 aggregate.py                # the scorecard again, without re-running
```

The historical minimum tool-call and token floors have been removed. Efficient
answers are eligible; correctness and product participation are checked
separately. The commands above default to protocol 1 and do not create v2 runs.

For a v2 run, explicitly select the protocol and supply the reviewed oracle:

```bash
node run.ts --protocol 2 --record REST-001 --contestant pi-codex --runs 1 \
  --max-tokens 2000000 --max-cost-usd 5 --timeout 300 \
  --oracle-bundle ../verification/evidence/REST-001/oracle-bundle.json \
  --trusted-oracle-sha256 33b42c1fca9396918b35c584076cca9cb5351ceb09d59889d5c195fd050d12d6 \
  --artifact-root ../verification/evidence/REST-001
```

Confirm the contestant name against `contestants.json` before invoking a paid
provider. Oracle preflight can be run without any model call; the exact command
is in the [v2 implementation status](../docs/methodology/implementation-status-v2.md).
V2 requires explicit `--max-tokens`, `--max-cost-usd` and `--timeout` values.
These are **per-attempt** ceilings; repeated/multi-contestant CLI runs do not
create a shared campaign allowance. A detached worker lets the parent enforce
the total deadline across setup, product playbook and model execution, even
while a native adapter blocks. A bounded 100ms abort grace preserves evidence.
Native product subprocesses also retain their own 60-second caps.

Before each provider request, the harness reserves the full catalog context
window and bounded output at the largest base/tier USD rates. Codex reserves
its full catalog output limit because its adapter ignores a smaller output
cap. Capped Codex requests use SSE without transport fallback; retry and
compaction are disabled. Unsupported/insufficient reservations fail before a
request. These bounds assume provider catalog limits/rates; SDK cost is a
catalog estimate, not an invoice. Aborted/failed requests retain their pending
reservation as unknown usage for conservative campaign accounting.

V2 answers, reports and runner-owned receipt envelopes live under
`answers/runs/v2/`. The runner checks pinned clean revisions, rechecks estate
identity around attempts and product calls, and rejects evaluator artifacts
inside the searchable estate. `aggregate_v2.py` independently regrades reports,
checks receipt and artifact hashes, rejects replayed attempts, and separates
cohorts by model, prompt, harness, estate, index, oracle and budget policy.
Use `--run-group <label>` to place revised runs under a separate
`answers/runs/v2/<label>/` directory. The label accepts letters, digits,
underscores and hyphens, up to 64 characters, and cannot escape its parent.
Aggregation also exposes the evaluation revision and trusted scoring policy.
The scorecard's `cost_accounting` includes verified failed-attempt costs and
unsettled exposure. If a failure has no trustworthy cost evidence, its total
exposure bound is reported as unavailable, rather than zero.

Scoring is automatic. Each run prints its own score breakdown, and the corpus
scorecard prints once at the end — `aggregate.py` is only needed to re-read it
later. Answers and their `.score.json` land in `answers/runs/`; an answer that
fails schema validation is kept for diagnosis but never scored or aggregated.

`score.py score` and `aggregate.py` print text; both take `--json` for
machine-readable output.

Score by hand only for answers produced elsewhere:

```bash
cd scoring
python3 score.py score --ground-truth ../records/REST-001.json \
                      --answer ../answers/examples/REST-001-agent-only.json
python3 score.py marginal --ground-truth ../records/REST-001.json \
                          --baseline ../answers/examples/REST-001-agent-only.json \
                          --candidate ../answers/examples/REST-001-gortex.json
```

Tests, from the harness root: `python3 scoring/test_score.py`,
`python3 scoring/test_score_v2.py`, `python3 run/test_aggregate.py`,
`python3 run/test_aggregate_v2.py`,
`python3 -m unittest discover -s verification -p 'test_*.py'`,
`python3 scoring/test_saved_repairs.py`, `python3 history/test_original_replay.py`,
and `npm test` in `run/`. Executable Java boundary checks run separately as
described in [verification/README.md](verification/README.md).

Historical reports retain the schema and scorer hashes used when they were
created. The current aggregator rejects reports whose hashes differ from the
current files; this is an integrity check, not a new model failure. Preserve
those reports and analyze them with their original committed harness. Start
fresh cohorts under the checkpointed harness rather than editing old hashes
or mixing old and new scores. See the
[checkpoint and restart plan](../docs/methodology/2026-10-09-checkpoint-and-restart.md).

## Contestants

A contestant is a provider/model entry in `run/contestants.json`, resolved by
the [pi coding agent](https://pi.dev) SDK:

```json
"pi-sonnet": { "label": "agent-only", "provider": "anthropic",
               "model": "claude-sonnet-4-5" }
```

Product-backed contestants add a `product` entry:

```json
"pi-codex-repowise-first": { "label": "repowise-first",
  "provider": "openai-codex", "model": "gpt-5.6-terra",
  "product": {"kind": "repowise"}, "product_first": true },
"pi-codex-codebase-memory-first": { "label": "codebase-memory-first",
  "provider": "openai-codex", "model": "gpt-5.6-terra",
  "product": {"kind": "codebase-memory"}, "product_first": true }
```

The real integrations query pinned open-source Gortex, RepoWise, Codebase
Memory, SCIP, and Graphify artifacts described in `indexes/README.md`. SCIP and
Graphify are historical; RepoWise and Codebase Memory passed Gate 0 on
2026-09-18 and are active only in the product-first matrix. Before a model
can run, the harness verifies repository revisions, dirty state, product binary
or distribution identity, artifact hashes, and product freshness. Each answer carries
a `product_provenance` receipt. Product-backed findings also distinguish
`product_direct`, `agent_inferred`, and `file_search` attribution.

## Product-first runs

A contestant with `"product_first": true` runs the primary product-quality
track instead of natural
adoption. The harness queries the product before the model exists, so product
evidence is guaranteed to enter the analysis rather than depending on the agent
choosing to ask — in the natural-adoption pilot it usually did not.

Phase A derives a change anchor from the record's diff and runs a frozen
playbook in `run/playbook.ts`: the same four intentions for every product —
locate, references, expand, tests — expressed in each product's native surface.
A product with no native answer for an intention has no step for it, and that
absence is a result. Phase B gives the model the question, the candidate set and
every receipt, plus file-search tools and `PHASE_B_PRODUCT_BUDGET` further
product calls, and asks it to verify rather than rediscover.

The anchor is derived mechanically from the supplied diff: changed identifier,
then changed literal, shared source context, or repository name as a fallback.
Diff headers do not become symbols. Literal-only and comment-only changes,
including `INT-001`, `INT-002` and `KAFKA-004`, remain eligible; the anchor never
uses hidden expected findings.

Every answer carries `product_playbook`, which is `null` on a natural-adoption
run. Do not compare the two: one measures whether the agent asks, the other
measures what the product returns when it is asked for it.

A product-first run is comparison-eligible only if every scheduled playbook
step reached a real product adapter, every attempt produced a receipt, and at
least one call succeeded. A single ceremonial call is insufficient. Product
failures remain evidence about the product; a missing or bypassed tool makes
the run ineligible.

Each product subprocess is capped at 60 seconds. A timeout is persisted as a
failed product receipt and cannot silently stall or disappear from a run.

Attribution is not the utilization metric here. Phase B asks the agent to verify
candidates, and verification re-attributes them, so `product_direct` under-counts
badly — a run can carry every playbook item into its answer and still report
zero. Measure overlap between the playbook's output and the final findings.

The complete current protocol, gates, staged run order, and interpretation
rules are in `../docs/methodology/product-first-real-product-evaluation.md`.

`run/indexes.ts` contains the old harness-built simulations. They are disabled
by default and their contestant descriptions say `LEGACY SIMULATION`. Historical
scores from those indexes must not be presented as product benchmarks.

Everything else is harness policy and cannot vary: identical prompt apart from
the index pointer section, read-only tools, no thinking, no local PI
configuration, and an isolated copy of the ten fixture repositories that the
tools refuse to read outside of. Each valid answer records the PI version,
model, prompt and estate hashes, index hash, tool-call count, and the commit
state of every fixture repo.

## Scoring

V2 uses exact repository-qualified symbols, directed contract edges and
structured remediation predicates. It reports precision/recall, complete repair
alternatives and critical downstream action misses. Independent oracle
attestations and live product receipts are separate evidence channels. There is
no composite that trades correctness against cost. Free-text explanations and
arbitrary source citation truth are not automatically verified. See the
[v2 scoring contract](../docs/methodology/v2-scoring-contract.md).
Revision 2.1 permits exact, independently proved equivalences through a trusted
oracle; it does not perform fuzzy normalization. Scoped-reference mode separates
required actions from full-plan correctness, labels supporting extras unverified,
and describes precision as reference overlap. Wrong protected identities/values
and established contradictions remain failures.

The following describes the unchanged **historical v1 scorer**:

Composite score, with weights renormalized when a manual rubric input is
missing:

| Component | Weight | Source |
|---|---|---|
| cross_repo_recall | 35% | severity-weighted repos matched vs ground truth |
| contract_recall | 20% | severity-weighted contracts matched vs ground truth |
| precision | 15% | overall matched / reported items across all kinds |
| evidence_quality | 10% | findings with a valid tier and evidence string |
| freshness | 10% | manual rubric 0–1, never model-supplied |
| latency | 5% | min(1, target_seconds / elapsed) |
| operational_cost | 5% | manual rubric 0–1, never model-supplied |
| critical_penalty | 15% | 1 - 0.25 per critical_runtime_dependency miss, floor 0 |

`symbol_recall` (severity-weighted, prefix-matched) and `test_recall` are
reported unweighted in the composite, for diagnostics.

False negatives outrank precision: recall is severity-weighted and every
critical miss also lowers `critical_penalty`. Ground truth assigns each item
a criticality, which scoring maps to a miss severity — see
`schema/change-ground-truth.schema.json`.

Each finding also declares an evidence tier: `compiler` (very high),
`extracted` and `contract_matched` (high), `inferred` (medium), `hypothesis`
(investigate). A structurally invalid finding is split out, counted as
`schema_invalid` in `evidence_quality` and in the precision denominator, and
listed in `invalid_findings`. It never voids the whole run.

Matching is case-insensitive and whitespace-normalized; contract types are
normalized (`asyncapi` → `kafka`, `openapi` stays distinct from `rest`) and
contract keys are `(type, identifier)`. See the Identifier Convention in
`run/prompt-template.md` for canonical forms. Symbols match on
declaring-type prefix, so a field reference credits its type.

An answer with `synthetic: true` is never scored. An answer whose `change_id`
differs from the ground truth aborts scoring. Only `frozen` records may be
scored (`--allow-draft` overrides for debugging).

Failed runs emit `<stem>.rejected.json` so the scorecard counts them.
Answers are never overwritten: a repeated stem is suffixed with its
`run_started_at` timestamp. Score reports carry `answer_sha256`, which
aggregation verifies to reject stale pairings.
