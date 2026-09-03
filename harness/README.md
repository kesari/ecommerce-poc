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
python3 aggregate.py                # the scorecard again, without re-running
```

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

Everything else is harness policy and cannot vary: identical prompt, read-only
tools, no thinking, no local PI configuration, and an isolated copy of the ten
fixture repositories that the tools refuse to read outside of. Each valid
answer records the PI version, model, prompt and estate hashes, and the commit
state of every fixture repo.

## Scoring

Composite score, with weights renormalized when a manual rubric input is
missing:

| Component | Weight | Source |
|---|---|---|
| cross_repo_recall | 35% | repos matched vs ground truth |
| contract_recall | 20% | contracts matched vs ground truth |
| precision | 15% | matched / reported repos |
| evidence_quality | 10% | findings with a valid tier and evidence string |
| freshness | 10% | manual rubric 0–1 |
| latency | 5% | min(1, target_seconds / elapsed) |
| operational_cost | 5% | manual rubric 0–1 |

`symbol_recall` and `test_recall` are reported unweighted, for diagnostics.

False negatives outrank precision: one critical miss is worse than several
false positives. Ground truth therefore assigns each item a criticality, which
scoring maps to a miss severity — see `schema/change-ground-truth.schema.json`.

Each finding also declares an evidence tier: `compiler` (very high),
`extracted` and `contract_matched` (high), `inferred` (medium), `hypothesis`
(investigate). An invalid tier counts as `unclassified` and lowers
`evidence_quality`.

Matching is case-insensitive and whitespace-normalized; contract keys are
`(type, identifier)`. An answer whose `change_id` differs from the ground
truth aborts scoring.
