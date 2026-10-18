# First v2 model smoke results

Date: 2026-10-18. Model: `openai-codex/gpt-5.6-terra` for every attempted run.

Two runs produced schema-valid, independently regraded answers and passed the
v2 provenance/participation gates. RepoWise's run was interrupted by host sleep.
Codebase Memory was not launched because the remaining allowance could not
cover the conservative next-request reservation. No product ranking follows
from this smoke; it exposed problems in the evaluation contract itself.

## Execution and budget

The user approved sending isolated POC source context and product results to
OpenAI Codex and authorized US$5 total. Runs were sequential, on the same clean
ten-repository estate, task, harness and model. Each attempt had a 300-second
deadline and 2,000,000-token ceiling. Its cost ceiling was the campaign's
remaining allowance, so these are integration checks with different per-attempt
cost policies, not a matched-budget comparison cohort. No prompt, scorer,
record or harness code changed during the sequence.

| Configuration | Outcome | Recorded seconds | Reported tokens | Observed SDK cost | Conservative debit |
|---|---|---:|---:|---:|---:|
| Agent-only | Scored; provenance verified | 104.326 | 100,979 | $0.1183336 | $0.1183336 |
| Gortex-first | Scored; product participation verified | 60.828 | 162,228 | $0.1922148 | $0.1922148 |
| RepoWise-first | Deadline failure during host suspension; no final answer | 639.084, includes sleep | 54,893 settled so far | $0.0574972 | $3.7214972 |
| Codebase Memory-first | Not started: insufficient remaining reservation | — | — | $0 | $0 |

Total observed SDK cost is **$0.3680456**. The unresolved RepoWise request retains
its full **$3.664** maximum exposure; total conservative debit is **$4.0320456**.
The remaining **$0.9679544** cannot cover the next $3.664 reservation, so no fourth
provider run or paid retry was started. SDK dollar values use catalog rates;
the table is not an invoice or a claim that all reserved exposure was spent.

The [campaign ledger](../../harness/answers/campaigns/2026-10-18-v2-rest001-smoke.json)
binds answer/failure evidence hashes and records each debit. The saved
[scorecard](../../harness/answers/campaigns/2026-10-18-v2-rest001-scorecard.json)
includes failed-attempt usage and unknown exposure, rather than counting only
successful answers.

## Host interruption, not product latency

RepoWise started at 02:24:36 IST. The restricted
[sleep/wake evidence](../../harness/answers/campaigns/2026-10-18-host-sleep-window.txt)
records clamshell sleep at 02:25:21 for 593 seconds, followed by dark wake at
02:35:14. This accounts for almost all of the late 639-second deadline result.
The local watchdog cannot run while its host is suspended; on resumption the
expired attempt was rejected and its process group terminated. The four native
playbook calls succeeded, as did three later RepoWise calls. There is no final
model answer to grade, and no basis to call the interruption a product defect.

Future latency comparisons require an awake, stable execution host. An idle-sleep
inhibitor alone does not prevent deliberate lid-close suspension. Preserve host
interruptions as a separate reporting fact; do not turn this result into a
RepoWise accuracy miss or a 639-second product-latency measurement.

## What the completed answers show

Both completed answers identified Order as broken and needing a production
binding repair. Both described reading `postcode` while preserving the internal
`postalCode` passed into the existing Shipment request. The frozen strict scorer
nevertheless reports action recall 0 and edge recall 0 for both. Keep these
original scores and artifacts unchanged; the following is an independent
qualitative audit, not a replacement grade.

- **Baseline member notation:** the answer used
  `AccountClient.AddressResponse#postalCode()` while the oracle expects the
  dotted record-component name ending `.AddressResponse.postalCode`. Repository,
  operation and old/new values match. The explanation correctly locates the
  `Map.of` null failure before Shipment HTTP and quote persistence. The zero
  action score is a representation miss, not evidence that the agent missed
  the required behavior change.
- **Contract timing:** the answers use `postcode` for the Account-to-Order
  response edge, whereas the oracle uses `postalCode`. The prompt assumes the
  proposed change applied and asks for the relevant serialized field without
  defining whether edge identity describes the original or changed contract.
  Direction and route are correct. This is a protocol ambiguity.
- **Repair location:** Gortex's answer identifies `AccountClient.address` as
  the read-mapping repair target rather than the reference DTO component.
  The public prompt permits equivalent read-mapping repairs, but the scorer
  accepts only the exact reference target. A method-level mapping is a candidate
  alternative to verify, not automatically an established wrong repair.
- **Scope remains important:** both answers include structured Shipment claims
  beyond the established server boundary. Failure before HTTP does not establish
  Shipment server behavior as unchanged. Such claims must not gain verified
  runtime credit. Gortex also recommends synchronizing the producer's committed
  OpenAPI snapshot. That recommendation has source support: the committed YAML
  retains `postalCode` and `OpenApiContractTest` compares it with generated
  documentation. It is outside the executed serialization/checkout proof; it
  should not be called invented or assumed unnecessary. The current smoke does
  not settle the broader contract-validation obligation.
- **Supporting symbols:** the answers list real supporting members beyond the
  record's expected singleton. The prompt does not define an exhaustive symbol
  set. An unmatched supporting member is not automatically a proven false
  statement; current precision measures overlap with the scoped expected set.

Gortex made 10 adapter invocations: 9 succeeded and 1 failure was retained. All
5 scheduled playbook steps have receipts, and 5 final claims cite product receipt
IDs. This establishes participation and attribution, not a causal improvement
over the single baseline. RepoWise has 7 successful native receipts including
all 4 playbook steps, but its model result remains incomplete. The earlier
Codebase Memory native-only smoke remains valid; it is not a scored model run.

## Required repairs before the 60-run comparison

1. Freeze an explicit public output convention: dotted baseline-source member
   identities, and a clear before/after convention for edge fields/topics.
   Use unrelated examples, not scenario-specific expected answers.
2. Define the set semantics of symbols, repositories and extra supporting
   claims. Distinguish unverified/out-of-scope additions from independently
   demonstrated false positives; do not describe scoped overlap as unrestricted
   correctness precision.
3. Independently execute permitted mapping alternatives before expanding their
   accepted action predicates. If accessor notation is accepted, bind aliases
   to exact source-verified record components; do not restore prefix or fuzzy
   matching across unrelated symbols.
4. Freeze a new cohort after those repairs and use a stable awake host with a
   separately authorized campaign allowance. Preserve these development smokes
   and original scores; do not silently regrade them or pool incompatible runs.

The integration and conservative cost guard worked. This smoke does not yet
establish which product improves correct cross-repository remediation.
