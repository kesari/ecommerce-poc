# Ground-Truth Harness

Frozen ground truth + deterministic scoring for the cross-repo change-impact POC.
Python 3 stdlib only, no dependencies.

## Workflow

1. Author ground-truth records in `records/` **before** running any contestant.
   One JSON file per historical change, validated by
   `schema/change-ground-truth.schema.json`.
2. Run each contestant independently against the same `query`. Capture answers as
   JSON per `schema/contestant-answer.schema.json` into `answers/`.
3. Score every answer. Compare contestants on composite score, miss severities,
   and provenance.

## Commands

```bash
cd scoring

# score one answer
python3 score.py score \
  --ground-truth ../records/REST-001.json \
  --answer ../answers/REST-001-agent-only.json

# graph marginal value: what did gortex find that agent-only did not?
python3 score.py marginal \
  --ground-truth ../records/REST-001.json \
  --baseline ../answers/REST-001-agent-only.json \
  --candidate ../answers/REST-001-gortex.json

# run the test suite
python3 -m unittest test_score
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
