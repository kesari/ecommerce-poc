# 9 October 2026 checkpoint and restart

This checkpoint preserves the product integrations and existing evidence before
evaluation resumes on `codex/restart-product-evaluation`. It is a continuation
from the committed checkpoint; the implementation and historical answers remain
available.

## Verified status

| Area | Status on 2026-10-09 |
|---|---|
| Fixture estate | All ten repositories match the pinned revisions and have clean working trees. |
| Ground truth | 15 frozen records: six REST, four Kafka, two database, three internal. The roughly 30-record target remains unfinished. |
| Harness | Product-first orchestration, product receipts, provenance checks, guarded file tools, scoring, and aggregation are implemented. |
| Active cohorts | Agent-only, Gortex-first, RepoWise-first, Codebase Memory-first; all configured with `gpt-5.6-terra`. |
| RepoWise and Codebase Memory | Documented Gate 0 smoke checks passed on 2026-09-18; no saved scored model runs exist for either product. |
| Saved model runs | Latest recorded start is 2026-09-07. These precede the new candidate pilot. |
| Local artifacts | Artifact files for all five real products exist and match their manifest SHA-256 hashes. This does not establish live service availability. |
| Gortex service | Daemon unreachable during the checkpoint tests; two integration checks skipped. |

SCIP, Graphify, natural-adoption runs, and synthetic indexes remain historical
evidence. They are outside the active four-cohort comparison.

## Verification

The checkpoint checks completed on 2026-10-09:

- Runner: 38 tests passed, two Gortex checks skipped, zero failures.
- Scorer: 32 tests passed.
- Aggregation: eight tests passed.
- All ten fixture repository revisions and clean states verified.
- All five real-product artifact sets checked against their manifests.

Fresh model runs and fresh native-query smoke checks for the active products
were not performed as part of this checkpoint.

## Historical score integrity

The current aggregator rejects 222 saved score reports because their recorded
answer-schema hash differs from the current schema, and five because their
scorer hash differs. These are compatibility exclusions, not failed reruns or
evidence that the underlying answers were lost.

Keep historical answers and reports unchanged. To reproduce an old scorecard,
use the corresponding committed schema, scorer, and harness. Any deliberate
rescoring must produce separately identified reports with current provenance;
never replace historical hashes merely to make aggregation accept them.

The historical runs do not establish a winner in the current comparison.

## Restart sequence

1. Commit and push this checkpoint on the current development branch, then
   create `codex/restart-product-evaluation` from that commit.
2. Restore Gortex and RepoWise service availability. Recheck pins, product
   identity, artifacts, and a deterministic REST-001 native playbook smoke for
   each active product. Record failures before starting model runs.
3. Freeze the harness, prompt, product versions, playbooks, estate, model, and
   run policy for the new cohort. Run REST-001 five times for each of the four
   cohorts: 20 fresh model runs in total.
4. Review receipt completeness, successful invocations, candidate overlap with
   ground truth, agent acceptance and recovery, critical misses, token usage,
   latency, and repeat-to-repeat stability. Separate retrieval quality from the
   agent's final score.
5. Expand to a balanced five-scenario slice only after reliable operation and
   evidence that product candidates reach the model. Preselect the slice before
   inspecting its outcomes. Expand further only if the slice supports it.

The immediate deliverable is a trustworthy four-way REST-001 pilot with a
documented decision about further evaluation. Corpus expansion and full-corpus
runs follow that decision. Track C currently excludes INT-001, INT-002, and
KAFKA-004 because their diffs produce no usable mechanical symbol anchor.

The [real-product methodology](product-first-real-product-evaluation.md) remains
the authority for eligibility and interpretation. Overlapping results are
inconclusive; setup smoke success alone is not a product-quality result.
