# Ground-Truth Harness

Frozen ground truth + deterministic scoring for the cross-repo change-impact POC.

- `run/run.ts` runs the contestants. Built on the [pi coding agent](https://pi.dev)
  SDK; needs Node 22.19+ and one `npm ci`.
- `scoring/score.py` and `run/aggregate.py` score and roll up. Python 3 stdlib
  only, no dependencies.

## Workflow

1. Author ground-truth records in `records/` **before** running any contestant.
   One JSON file per historical change, validated by
   `schema/change-ground-truth.schema.json`.
2. Run each contestant independently against the same `query` with `run/run.ts`.
   It validates answers against `schema/contestant-answer.schema.json`, writes
   them into `answers/runs/`, and scores only valid output.
3. Compare contestants on composite score, miss severities, and provenance.
   `run/aggregate.py` reports the median and spread across runs, because a
   single agent run measures luck as much as capability.

## Runner

`run/run.ts` is the harness. It is built on the [pi coding agent](https://pi.dev)
SDK, so a contestant is a provider/model configuration in
`run/contestants.json`, not a separate CLI. Every contestant gets the identical
prompt, fixed read-only tool set, disabled thinking, system prompt and isolated
estate copy; only the provider/model varies.

```bash
cd run
npm ci --ignore-scripts             # once
npm test

node run.ts --record REST-001 --runs 1
node run.ts --record all --contestant pi-qwen3-coder --runs 3
```

Each run copies an explicit allowlist of the ten fixture repositories to a temp
directory, excluding `target`, `node_modules`, `.git`, `dist`, and `build`.
PI's read, grep, find, and list operations additionally reject paths whose real
path escapes that copy. Extensions, skills, prompt templates and
`CLAUDE.md`/`AGENTS.md` discovery are disabled, so a scored run does not inherit
local PI configuration. The runner validates the frozen record and contestant
answer before scoring. Invalid answers remain available for diagnosis but never
receive a new score or enter the aggregate scorecard.

For reproducibility, every valid answer records the PI version, provider/model,
local model digest when available, prompt and contestant-configuration hashes,
an estate content hash, and the commit/dirty state of every fixture repository.

Add a contestant by naming any provider/model pi can resolve:

```json
"pi-sonnet": { "label": "agent-only", "provider": "anthropic",
               "model": "claude-sonnet-4-5" }
```

The provider needs credentials configured in PI. Tool access and thinking level
are harness policy and cannot vary by contestant.

## Scoring commands

`run.ts` scores every run it produces. Score by hand when an answer came from
somewhere else. The score command rejects schema-invalid records and answers.

```bash
cd scoring

# score one answer
python3 score.py score \
  --ground-truth ../records/REST-001.json \
  --answer ../answers/examples/REST-001-agent-only.json

# graph marginal value: what did gortex find that agent-only did not?
python3 score.py marginal \
  --ground-truth ../records/REST-001.json \
  --baseline ../answers/examples/REST-001-agent-only.json \
  --candidate ../answers/examples/REST-001-gortex.json

# run the test suite
python3 -m unittest test_score

# test aggregation rejection from the harness root
python3 -m unittest discover -s run -p 'test_*.py'
```

Corpus scorecard across every scored run, from the harness root:

```bash
python3 run/aggregate.py
```

## Metrics

Weighted composite (weights renormalize when a manual rubric input is missing;
excluded components are listed in the report):

| Component | Weight | Source |
|---|---|---|
| cross_repo_recall | 35% | repos matched vs ground truth |
| contract_recall | 20% | contracts matched vs ground truth |
| precision | 15% | matched / reported repos |
| evidence_quality | 10% | findings with valid tier + evidence string |
| freshness | 10% | manual rubric 0–1 (`freshness_score`) |
| latency | 5% | min(1, target_seconds / elapsed) |
| operational_cost | 5% | manual rubric 0–1 (`operational_cost_score`) |

`symbol_recall` and `test_recall` are reported unweighted for diagnostics.

## Miss severity

Ground truth classifies every item so misses can be triaged:

- repository criticality → severity:
  `critical → critical_runtime_dependency`,
  `contract_consumer → contract_consumer`,
  `test_only → test_suite`,
  `informational → informational`
- symbols default to `informational`, contracts to `contract_consumer` unless overridden.

Per the POC docs, false negatives outrank precision. A single critical miss is
a worse outcome than several false positives.

## Evidence tiers (provenance)

`compiler` (VERY HIGH), `extracted` and `contract_matched` (HIGH),
`inferred` (MEDIUM), `hypothesis` (INVESTIGATE).
Findings without a valid tier count as `unclassified` in
`provenance_distribution` and drag down `evidence_quality`.

## Conventions

- Matching is case-insensitive and whitespace-normalized; contract keys are
  `(type, identifier)`.
- Answers must carry the same `change_id` as the ground truth or scoring aborts.
