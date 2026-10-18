# Bottom-up verification implementation status

Date: 2026-10-16. Updated: 2026-10-18. Branch: `codex/verify-impact-benchmark`.

The first implementation milestone of the [approved repair plan](bottom-up-verification-repair-plan.md)
is complete and the next local gates have passed: acceptance criteria, a separate
v2 scorer, three verified scoped records, and a runner that requires independently
pinned evidence and explicit run ceilings before calling a model. Native adapter
smoke succeeded for all three active products. After explicit user approval,
the [18 October model smoke](2026-10-18-v2-smoke-results.md) produced two scored
answers. Host sleep interrupted RepoWise; the remaining conservative allowance
prevented Codebase Memory's model run. Observed SDK cost is $0.3680456, with
$4.0320456 total conservative exposure/debit under the $5 ceiling. The smoke
exposed representation and scope ambiguities; no product ranking is supported.
The [revision 2.1 repair](2026-10-18-scorer-v2.1-repair.md) now resolves those
matching ambiguities with exact proof-bound equivalences and scoped reporting.
Saved-answer regression checks give both required consumer repairs credit while
retaining the Gortex obligation conflict. Original scores remain reproducible.
The separately authorized revision 2.1 RepoWise and Codebase Memory reruns both
completed and satisfied the bounded repair, at a combined SDK estimated cost of
$0.2830784. Contract-edge recall was 1/1 for RepoWise and 0/1 for Codebase Memory,
which supplied method names instead of repository identifiers in its edges.
Codebase Memory's optional trace flag is now corrected in adapter 1.4.1 and
verified through the real pinned CLI/index. The
[broader discovery plan](2026-10-18-broader-discovery-plan.md) proposes twelve
new cases and paired comparisons against fresh agent-only controls; those cases
and the campaign have not been implemented or run.

## Implemented

- [Acceptance contract](acceptance-contract-v2.md): runtime breakage, necessary
  production edits and validation-only obligations are separate claims.
- [V2 scoring contract](v2-scoring-contract.md): exact repository-qualified
  symbols, directed provider/consumer edges, structured remediation predicates,
  complete repair alternatives and critical downstream action misses. Cost and
  latency stay separate from correctness. Free-text explanation and arbitrary
  citation truth are not automatically graded as established evidence.
- Trust checks bind the entire acceptance record, pinned source snapshot and
  execution artifacts to an externally supplied oracle digest. Saved answers
  cannot approve their own oracle. Historical v1 records, scorer, schemas and
  saved answers are unchanged.
- Runner preflight rejects hidden evaluator artifacts in the searchable estate,
  external symlinks, dirty/wrong fixture revisions and invalid oracle evidence.
  Estate identity is rechecked around attempts and product calls. Product-first
  steps must correspond to actual invocation receipts; model-authored stamps
  cannot substitute for adapter participation.
- The generic prompt no longer teaches the historical REST failure story.
  Efficient runs are eligible without minimum token/tool-call floors.
  Literal/context/repository anchors keep non-symbol changes in the experiment.
- V2 receipt envelopes are written by the runner before parsing a model answer.
  The separate v2 aggregator regrades saved verdicts, verifies artifacts and live
  receipt envelopes, rejects duplicate attempts even when files are copied or
  reformatted, and keeps incompatible cohorts separate.
- Explicit per-attempt token, catalog-cost and time ceilings are enforced. Each
  provider request reserves the full model context plus bounded output at the
  maximum base/tier rates. Codex reserves its full catalog output limit, uses
  SSE and disables retry/compaction. A detached worker enforces the deadline
  even when synchronous native calls block; a 100ms abort grace is bounded.
  Failed attempts retain partial output, receipts, usage and unsettled maximum
  exposure. Policy and measured usage must match the protected runner envelope.
  Aggregation separates budget policies and includes verified failure costs.
  A local watchdog cannot run while the host is suspended; the RepoWise smoke
  demonstrated delayed deadline rejection after clamshell sleep. An awake
  execution host is required for wall-time comparison.

## Executed scenario evidence

Each scenario has baseline, proposed-change, repaired and deliberately faulted
repair phases. The fault must fail an unchanged acceptance assertion. These are
bounded execution checks, not full distributed end-to-end tests.

| Scenario | Real boundary exercised | Observed outcome | Promotion |
|---|---|---|---|
| REST-001 | Actual Account response serialization over HTTP into actual Order clients and checkout code | Rename binds null; `ShipmentClient` fails at `Map.of` before Shipment HTTP and quote save. Consumer read alias restores postal code, Shipment request and quote. Wrong alias fails `REPAIR_BINDING`. | Verified v2 record and pinned oracle |
| INT-002 | Actual Shipment cache/service against disposable Valkey | Prefix change causes a benign cold miss, preserves the old key and recomputes the expected estimate; the new key serves the next hit. Return-null fault fails `ESTIMATE_COMPATIBILITY`. | Verified v2 record and pinned oracle |
| KAFKA-001 | Actual Order producer and Spring-registered Basket/Shipment listeners against disposable Kafka | New topic receives the producer message; old-topic consumers remain idle during the bounded window. Both subscription repairs restore handling. Leaving Basket on the old topic fails `BASKET_CONSUMER_DELIVERY`; old-topic controls confirm listener health. | Verified v2 record and pinned oracle |

Reproduction commands, reviewed test/criteria digests, controlled dependencies
and limitations are in [the verification guide](../../harness/verification/README.md).
Portable logs, exact patches, observations and manifests are under
`harness/verification/evidence/`. Pinned service working copies are not edited.
Kafka and Valkey containers used for verification were cleaned up.

The REST execution contradicts the historical null-to-Kafka/DLQ account for
normal quote creation. Preserve that v1 history, but do not reuse its narrative
as verified v2 truth. Database durability, provider routing/security, real
Shipment server behavior, retry/DLQ migration, UI behavior and a complete
distributed journey remain outside these checks.

## Local verification

265 deterministic tests passed after revision 2.1: 78 Node runner tests,
32 v1 scorer tests, 73 v2 scorer tests, 4 saved-answer regression tests,
17 historical aggregation tests, 27 v2 aggregation tests, 32 verifier/oracle
tests and 2 original-version replay tests.
One Gortex route-surface check was skipped because its daemon was unreachable; the escalated native
playbook succeeded. The first 12 executable scenario phases and four additional
method-mapping phases produced their specified outcomes, including four rejected
faulted repairs. No TypeScript type-check or linter gate is configured or claimed.

After the trace adapter fix, the full Node suite passed **79 tests**, with the
same one daemon-dependent skip. Its new regression first reproduced the invalid
flag, then obtained a nonempty traversal through the real Codebase Memory
adapter. A separate [native receipt](../../harness/indexes/smokes/2026-10-18-trace-fix/codebase-memory-trace.json)
records two callers and eleven callees for a uniquely resolved indexed function.
These are graph results, not independent proof of every relationship. No fresh
model call was made for this fix; the earlier Python checks are unchanged.

REST oracle preflight succeeds without contacting a provider. From the study root:

```sh
python3 harness/scoring/score_v2.py preflight \
  --record harness/records/v2/REST-001.json \
  --oracle-bundle harness/verification/evidence/REST-001/oracle-bundle.json \
  --trusted-oracle-sha256 33b42c1fca9396918b35c584076cca9cb5351ceb09d59889d5c195fd050d12d6 \
  --artifact-root harness/verification/evidence/REST-001
```

The reviewed pins are recorded in
[`approved-pins.json`](../../harness/verification/rest001/approved-pins.json)
for REST and the [verification guide](../../harness/verification/README.md) for
all three scenarios.
Recomputing a digest after changing an oracle is not independent approval.
REST-001's question explicitly limits the runtime boundary, so this initial
slice validates integration and remediation scoring; it cannot support a
general cross-repository discovery ranking.

## Remaining gates

Follow the [broader discovery plan](2026-10-18-broader-discovery-plan.md):

1. Cover every exposed native operation, clarify repository-valued edge fields,
   and extend action predicates only for independently proved new scenarios.
2. Build twelve less guided cases, including cross-language, semantic, messaging,
   private-storage and compatible controls. Freeze their execution evidence
   before any held-out model run; retain unresolved surfaces as unresolved.
3. Run fresh development checks for all four arms under one frozen protocol,
   including agent-only and Gortex. Preserve the original smoke and reruns.
4. With an approved campaign budget, execute the proposed 144 paired held-out
   attempts and compare each product against agent-only. Keep correctness,
   unsupported claims, failures and efficiency separately visible.

CLI ceilings apply per attempt. A campaign must separately account for all
attempts, including failures. The planned smoke sequence reserves each next
request against the remaining US$5 allowance; unknown final usage debits the
outstanding request's maximum exposure. SDK dollar values assume catalog rates
and provider limits; they do not certify actual invoiced charges.

The active candidate matrix remains agent-only, Gortex-first, RepoWise-first
and Codebase Memory-first. There is no evidence yet to choose a winning product.
