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

Runs below either floor — fewer than `--min-tool-calls` guarded tool calls
(default 10) or fewer than `--min-tokens` tokens consumed (default 8000) — are
rejected as thin runs. The answer is kept for diagnosis with a `.rejected.json`
stub but never scored. This prevents a zero-search answer from passing merely
because it was verbose.

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
`python3 run/test_aggregate.py`, and `npm test` in `run/`.

## Contestants

A contestant is a provider/model entry in `run/contestants.json`, resolved by
the [pi coding agent](https://pi.dev) SDK:

```json
"pi-sonnet": { "label": "agent-only", "provider": "anthropic",
               "model": "claude-sonnet-4-5" }
```

The active product-backed contestants add a `product` entry:

```json
"pi-codex-scip-real": { "label": "scip", "provider": "openai-codex",
                         "model": "gpt-5.4", "product": {"kind": "scip"} },
"pi-codex-gortex-real": { "label": "gortex", "provider": "openai-codex",
                           "model": "gpt-5.4", "product": {"kind": "gortex"} }
```

The real integrations query pinned open-source scip-java plus scip-search,
Gortex, and Graphify artifacts described in `indexes/README.md`. Before a model
can run, the harness verifies repository revisions, dirty state, product binary
or package identity, artifact hashes, and Gortex freshness. Each answer carries
a `product_provenance` receipt. Product-backed findings also distinguish
`product_direct`, `agent_inferred`, and `file_search` attribution.

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
