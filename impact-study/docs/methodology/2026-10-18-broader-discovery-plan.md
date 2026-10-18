# Broader cross-repository discovery comparison

Date: 2026-10-18. Status: proposed execution plan; broader cases and model
campaign are not implemented or run. This is the next experiment after the
[revision 2.1 smoke](2026-10-18-scorer-v2.1-repair.md), replacing repetition of
the three guided cases as the next tool-selection milestone. Historical results
remain separate.

## Decision to make

Does adding Gortex, RepoWise or Codebase Memory help the same agent discover
otherwise missed downstream failures and necessary coordinated edits? Compare
each product-first treatment directly with fresh agent-only runs. A product
must improve correctness, or achieve comparable correctness at lower measured
cost/time, to justify its operational overhead. Setup and index maintenance
costs are reported separately from per-query costs.

REST-001 already names Order, the HTTP binding, quote creation and the requested
repair. It proves integration and repair scoring, but provides much of the
discovery answer. Its three accepted plans do not show that a tool adds value.

## 1. Finish the development gates

The Codebase Memory trace fix is complete: adapter 1.4.1 uses `--depth 5`, and
its regression exercises the pinned 0.11.0 binary and index, not a fake CLI.
The [native smoke](../../harness/indexes/smokes/2026-10-18-trace-fix/codebase-memory-trace.json)
records a resolved function, callers, callees and a successful receipt, with no
model calls. Native traversal does not establish that every graph edge is true.
The earlier failed receipt and scored rerun are unchanged.

Before broader model runs:

1. Exercise every exposed operation, including optional trace operations, in
   the native adapter checks. Test a uniquely resolved indexed name as well as
   empty/ambiguous responses; distinguish those responses from invocation errors.
2. Use only the existing three v2 cases and fifteen v1 scenarios for prompt,
   schema, verifier and adapter development. Historical scenarios are development
   material, even when their old expected answers have never been executed.
3. Clarify that edge provider/consumer fields take repository IDs; validate
   membership against the public estate inventory. Do not infer repositories
   from arbitrary class-name prefixes. Add a generic, unrelated output example.
4. Extend structured action predicates for the new cases before freezing them.
   Current v2 accepts only JSON read/alias and topic subscription/publication
   actions; it cannot yet score route, SQL, arithmetic or UI repairs. Add only
   the predicates required by proved cases, with positive, wrong-repository,
   wrong-value and missing-action tests. Use a new evaluation revision and
   preserve the 2.1 scorer, prompt and scores for replay.

**Gate:** local checks pass, all exposed native operations are exercised, and
the answer vocabulary can express each accepted repair without prose grading.

## 2. Remove answer hints without hiding the task

Give every arm the same public task, complete applicable upstream patch,
repository inventory and original source estate. The origin and filenames in
the patch remain available; those are normal developer inputs.

Use a common question such as:

> Assess this proposed change across the supplied repositories. Identify
> behavior that breaks, repositories needing production changes, validation-only
> obligations, directed contract dependencies, and a sufficient repair plan.
> Preserve the requested change and the stated compatibility requirements.
> Support findings with source evidence and state unresolved claims.

Do not name expected downstream services, a failure mechanism, the required
repair count, or the causal path. State legitimate compatibility and rollout
requirements as public task inputs, identically for all arms; otherwise several
different repair plans may be equally reasonable. Use the same task template
for controls, without labels such as “safe change” or “negative case”.

List supported evaluation surfaces once for the campaign: Java HTTP clients,
registered message listeners, proxy forwarding, web data consumption and private
storage boundaries. Put detailed oracle execution limits in the evaluator's
report, not answer-shaped per-case hints. Unexecuted surfaces stay unresolved.
The predictor remains read-only; executing its proposed implementation is a
separate experiment.

## 3. Build twelve new cases before measuring models

Use six families with two cases each. These are candidate templates, not verified
expected outcomes. Freeze exact patches, inputs and acceptance criteria after
independent execution, before any scored model sees them. Do not relabel an
existing v1/v2 patch as a holdout; use new contract locations or materially new
mutations, and record their relationship to the development cases.

| Family / case IDs | Candidate change | What the independent verifier must establish |
|---|---|---|
| Direct contracts / H01–H02 | Response shape or required-field changes at new Basket/Catalog/Shipment contract locations | Actual provider serialization and real consumers; identify who breaks and which read mappings restore behavior |
| Composition and web / H03–H04 | A new field/shape change crossing BFF forwarding and web display or submission | Real HTTP proxy forwarding plus web build and behavior tests consuming actual serialized responses; distinguish pass-through validation from an implementation edit |
| Messaging / H05–H06 | A new Payment/Inventory event-routing change and a separate consumed-payload change | Real broker, actual registered producer/listeners, delivery/liveness controls and domain-state assertions; test retry/DLQ behavior only if included in the declared contract |
| Meaning without a rename / H07–H08 | An amount-unit/conversion change with stable names, and a validation/default change with stable signatures | Concrete business-value assertions for valid, invalid and boundary inputs through consumers; freeze intended policy so preserving the change does not mean undoing it |
| Private storage / H09–H10 | A new private SQL mapping/migration change and a new internal cache representation change | Real PostgreSQL/Valkey and API observations; distinguish downstream runtime symptoms from repositories that actually require edits |
| Compatible controls / H11–H12 | An additive optional response field and an implementation change preserving a published contract | Relevant consumers continue to work under unchanged assertions; unnecessary coordinated edits must not earn discovery credit |

Include same-named fields, unrelated routes and non-consuming services as natural
distractors. Do not strip ordinary source tests, OpenAPI/AsyncAPI files or
architecture docs: these are legitimate evidence for every arm. Neither the
case catalog nor protected verifier material enters the searchable estate.

For each case, save baseline, changed, repaired and deliberately faulty-repair
execution. A compatible/no-edit case instead requires unchanged behavior after
the change and an incompatible fault caught by that same behavioral assertion.
Compile the changed origin so a broken producer build cannot masquerade as a
downstream discovery task. Freeze complete upstream edits needed to express the
requested change; only coordinated impact work belongs in the answer.

For real failures, test each claimed necessary action by removing or corrupting
it while keeping the other repairs. Prove at least one full sufficient repair
set and any supported alternatives. Record origin behavior, downstream behavior,
production edits and validation-only obligations separately for every inspected
repository. A private database can cause a public failure without requiring a
consumer edit; an unchanged schema can still change business meaning.

**Gate:** twelve executable, externally pinned case bundles, a source-to-assertion
coverage matrix, negative controls and no unresolved *critical* expectations.
Blocked infrastructure is reported as blocked. Replace an unprovable candidate
only before model execution, record why, and preserve the family balance.

## 4. Freeze a paired experiment

Run the same model and thinking policy in four arms: agent-only, Gortex-first,
RepoWise-first and Codebase Memory-first. Product-first means agent + product +
frozen native playbook; this experiment does not isolate the graph algorithm
from its orchestration. Keep natural adoption as a separate future experiment.

- First run two development cases, one breaking and one compatible/private,
  once per arm: **8 development runs**. Verify fresh baseline/Gortex operation
  as well as both repaired integrations. These do not enter held-out estimates.
- Then run **12 held-out cases × 4 arms × 3 independent sessions = 144 runs**.
  Start with one complete round of all 48 case/arm cells, then repeat twice.
  Order cases with a saved random seed; rotate arm order within each case.
  Treat this as exploratory evidence, not an assurance of statistical power.
- Reset sessions and scratch estates for every attempt. Freeze source snapshots,
  model/provider identity, prompts, adapters, indexes, playbooks and output rules.
  Public inputs differ only in the product treatment. Derive anchors only from
  the public patch/task; never tune a query using expected consumers or repairs.
- Give every arm identical file tools and total attempt ceilings, including
  product setup/playbook time. Proposed larger-case ceilings are 600 seconds,
  2,000,000 total tokens and US$5 estimated cost per attempt; confirm feasibility
  on development cases and freeze once before holdouts. Retain native-call and
  additional-query caps. Equal ceilings permit different actual consumption.
- Record warm-index query cost separately from index construction/update cost.
  Keep an awake host and fixed index state; do not silently switch RepoWise
  from structural/keyless retrieval to a hosted embedder mid-comparison.

A separately approved total campaign allowance is required before provider
execution. This task makes no paid calls and does not allocate the unused rerun
allowance to this plan. The campaign ledger reserves unsettled exposure before
every request. Stop when the next reservation cannot fit; retain partial blocks
and all failed attempts rather than rerunning until each arm succeeds.

## 5. Measure improvement against the matched baseline

The primary outcome is **complete supported impact and repair**, within each
case's proved surface: all critical downstream failures, required actions and
directed contract edges found, correct repository obligations, and no
independently disproved in-scope production edits or runtime claims. Unknown
critical claims prevent a complete-success verdict. Report coverage limits.
This is stricter than 2.1's `satisfies_any_repair`, which can pass while edge
recall is zero; implement it as a separate explicit result before the campaign.

Report each component alongside that outcome: critical misses, action and edge
recall, independently proved unnecessary edits, negative-control specificity,
unverified extras and infrastructure/invalid-output/budget failures. An unmatched
supporting fact is not automatically false, and a graph receipt is not proof of
a causal explanation. Keep all scheduled attempts in an operational success
denominator; also show correctness conditional on valid completed answers.

For each product, pair results with agent-only on the same case and repetition
block. Report product-only successes, baseline-only successes and ties, then
average repetitions within each case and weight cases equally. Report family
results so one repeated rename family cannot dominate. Repetitions are not
independent scenarios: uncertainty estimates resample cases, preserving their
repeats; report the sensitivity to grouping by the six families. Treat small
sample intervals cautiously and account for three product comparisons if making
formal significance claims.

Before execution, register a practical decision rule. Proposed rule: correctness
improvement needs at least a 10 percentage-point absolute gain in complete
success, a paired uncertainty interval above zero, and no observed deterioration
in compatible-control safety. If evidence does not meet it, report inconclusive
or no demonstrated benefit. Comparable accuracy alone does not prove equivalence;
show the uncertainty before claiming an efficiency-only benefit. Keep cost,
elapsed time, tokens, native failures and setup effort separate from correctness.

Audit product receipts for candidates that the final answer accepted, rejected
or extended with file search. Use source-bound checks or blinded human review
for relationship meaning. A matched win plus receipt support is evidence of
contribution, not proof that the agent could never find it unaided. An optional
later ablation may remove product results; do not add it opportunistically to
the primary campaign.

## 6. Preserve the test boundary and publish the result

Freeze verifier tests and trusted bundle digests before held-out predictions.
Keep protected records, repair patches, logs and scores outside all tool indexes.
Review new valid repair alternatives without runner identity, against unchanged
criteria; save the proof and regrade every arm under a new revision. Preserve
the original scores. Do not add permissive string matching for one product.

Deliver a per-case paired results table, family summaries, uncertainty, all
failure states, receipt contribution audits and separate efficiency/setup data.
Publish negative and null results. This estate can establish a benefit on these
tasks; a general product recommendation needs replication on another estate.

Implementation order: operation smokes and output/schema development; two
end-to-end development slices (cross-language positive and private/no-edit);
twelve independently verified holdouts; frozen scoring/aggregation and schedule;
approved budget; development runs; then the 144-run comparison. No bulk model
campaign starts while oracle or scoring coverage is still being constructed.
