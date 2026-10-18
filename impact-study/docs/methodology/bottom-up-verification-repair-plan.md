# Bottom-up repair plan for the cross-repository impact benchmark

Date: 2026-10-16. Status: approved plan; first implementation milestone completed.
The [implementation status](implementation-status-v2.md) records what has been
verified and which campaign gates remain open. The phases below preserve the
intended scope; they are not a claim that the entire comparison is complete.

## Objective and source

Determine whether adding a tool helps the same LLM/agent identify downstream
impact and necessary changes more correctly than guarded file search alone,
and measure the additional time, tokens, and operational effort separately.

The organizing principle comes from Alex Lavaee's
[Agents Write the Code. Your System Has to Prove It.](https://alexlavaee.me/blog/verifying-agent-written-code/):
define acceptance criteria before implementation, establish independent
deterministic checks from the bottom up, save their evidence, and reserve model
judgment for what those checks cannot decide. The phases below are our
application of that principle to this benchmark, not claims made by the article.

The prediction agent remains read-only. Executable patches and repairs belong
to the independent benchmark verifier. Agent-generated implementation is a
separate future experiment, not a prerequisite for this study.

## Current failures to repair

- The schemas represent affected repositories, symbols, contracts, and named
  tests, but not explicit remediation actions or causal paths.
- Contract scoring ignores consumer identities; symbol scoring ignores repo
  identity and accepts declaring-type prefixes in either direction.
- Evidence quality rewards a recognized evidence tier and nonempty text, not
  the truth of the explanation or the cited relationship.
- A direct scorer check awarded full marks to exact expected identifiers with
  unsupported explanations, even after replacing contract consumers and symbol
  repositories with incorrect identities.
- Scenario diffs are descriptive snippets rather than complete reproducible
  patches. KAFKA-004 expresses a new envelope restriction in a comment.
- The prompt teaches a provider rename -> null -> event -> downstream guard
  chain and a BFF exception closely matching REST-001.
- Runs under 10 tool calls or 8,000 tokens are rejected, even if correct.
- Test recall is diagnostic rather than part of the existing composite, and
  naming a test does not establish that it detects the proposed failure.
- Historical v1 results and the revised benchmark cannot be pooled.

## Verification order and gates

Each phase produces reviewable artifacts and has an exit gate. A failed gate
blocks claims based on the next layer, not unrelated implementation work.

### Phase 0: specify the evaluation contract

Write and review one acceptance matrix before changing the scorer. Each item
names the starting revisions, proposed change, observable outcome, independent
oracle, real dependencies, and the expected evidence artifact.

Define three distinct questions for every repository:

1. Does behavior break after the proposed upstream change, before repair?
2. Does this repository need a production edit under an accepted repair plan?
3. Does it need validation even when no production edit is required?

A downstream runtime failure is not proof that its repository needs editing.
Fixing an upstream binding can restore downstream behavior. Document permitted
compatibility strategies, such as a consumer alias or a producer compatibility
window, before evaluating answers. Accept multiple repair sets when they meet
the same contract; do not require the reference patch's exact implementation.

Retain the current 15 records as immutable v1 history. Introduce an explicit v2
protocol and record version with independently verified expectations. Do not
silently upgrade existing evidence notes into executable truth.

**Exit gate:** every pilot requirement maps to an observable check; affected,
edit-required, validation-only, unaffected, and unresolved have separate
meanings. No acceptance condition depends on an agent saying it is correct.

### Phase 1: specify claims and make scorer failures reproducible

Start with schema/JSON validation, repository and artifact identity checks, and
the existing repository checks. Preserve working checks while strengthening
their assertions. From the harness directory, the current test entry points are
`python3 scoring/test_score.py`, `python3 run/test_aggregate.py`, and `npm test`
inside `run/`. Check changed shell scripts with `bash -n` and use
`git diff --check`. Do not claim a TypeScript type-check or linter passed when
the repository has no corresponding configured gate.

Extend the record and answer schemas with structured actions and causal paths.
An action names a repository, file/symbol or contract location, action kind,
before/after behavior, condition or repair alternative, and supporting evidence.
A path names its source, destination, direction, relationship type, contract or
field, and evidence references for each edge.

Match assertions at the granularity they make: `(repo, symbol)` for symbol
identity and `(provider, consumer, type, identifier)` for contract edges. Keep
declaring-type matches as a separate coarse discovery metric; they cannot prove
a field-level remediation claim. Keep originating-repository discovery separate
from downstream-repository recall.

Define a small vocabulary of machine-checkable action predicates for the pilot,
such as reading a renamed JSON field or subscribing to a renamed topic. Match
structured claims to these predicates and accepted alternatives. Do not replace
identifier matching with prose substring matching, an exposed expected-answer
ID, or an unconstrained LLM grade.

Write a failing public-interface regression test for one demonstrated loophole,
then make the smallest correction, and repeat. Include:

| Challenge | Required result |
|---|---|
| Correct symbol name in the wrong repository | No credit for that symbol claim |
| Correct contract identifier, wrong consumer or direction | No credit for the claimed edge |
| Correct repository list, wrong required edits | Discovery credit only; action checks fail |
| Existing cited line unrelated to the claimed relationship | Citation existence may pass; relationship remains unverified |
| Broken or cross-snapshot receipt reference | Product provenance check fails |
| Missing critical action | Explicit critical miss; latency cannot compensate |
| Extra action in an independently established unaffected repo | False positive |
| Equivalent accepted repair strategy | Same action correctness as the reference strategy |
| Duplicate, reordered, or harmlessly reformatted claims | No extra credit; deterministic output |
| Malformed claim | Explicit invalid finding, not a silent match |

**Exit gate:** the demonstrated wrong-repo, wrong-consumer, and unsupported
evidence cases cannot receive full correctness/evidence credit. Expected scorer
outputs are reviewed literals or hand-derived values, not captured scorer output.

### Phase 2: prove the pilot ground truth through real boundaries

Start with three scenarios chosen before measuring product performance:

| Scenario | Independent behavior to establish |
|---|---|
| INT-002 | Cache-key change preserves user-visible behavior; no downstream production edit is needed |
| KAFKA-001 | A producer topic rename disconnects the real Basket/Shipment consumers unless an accepted migration is supplied |
| REST-001 | Establish exactly where renamed address JSON breaks real HTTP binding and whether/how the effect reaches Shipment |

For each scenario, prepare complete, reproducible patches in isolated disposable
copies, retaining original estate revisions and hashes. An upstream patch must
compile with necessary source-local edits, or its local compile failure must be
explicitly the behavior being tested. Do not mistake a broken producer build
for evidence of downstream runtime failure.

Run the same protected acceptance checks on:

- Baseline: unchanged estate behaves as specified.
- Changed: the proposed change produces the predicted observations. A benign
  change remains green; do not require every change to cause a failure.
- Repaired: an independently defined repair restores required behavior while
  retaining the requested upstream semantics.
- Faulted repair: a targeted fault reintroduces the failure and is caught.

Use real HTTP serialization/deserialization for REST boundary claims and real
Kafka/PostgreSQL through Testcontainers or isolated Compose for claims depending
on them. Avoid mocking the boundary under investigation. Unit tests and mocks
remain appropriate for unrelated local behavior.

Existing `OrderApiMockedTest` substitutes `FakeAccountPort` and therefore cannot
prove Account-to-Order compatibility. `OrderSagaIntegrationTest` exercises real
Kafka and PostgreSQL but seeds its order state; it does not establish the
complete Account-to-Order-to-Shipment journey.

Recheck REST-001's historical narrative rather than assuming it: `CheckoutService`
calls `shipments.estimate(..., address.postalCode(), ...)` before saving a quote.
This may prevent the proposed null from ever reaching an order-confirmed event
on the normal journey. Observe the first failure and any preconditions needed
for a transitive event path. Revise the v2 expectation if the execution
contradicts the old notes; retain the v1 record unchanged.

**Exit gate:** all three scenarios have saved baseline/change/repair observations,
negative assertions, patch identities, and bounded fault checks. Claims not
established by execution are explicitly marked unresolved or statically
supported, rather than labeled runtime-proven.

### Phase 3: verify the verifier with properties and targeted faults

Extend the deterministic unit tests with a small generated-input suite using
existing runtimes. Check invariants: scores stay within bounds; duplicate
claims add no credit; missing critical actions are visible; time/token changes
do not change correctness; permutation preserves results; stale or changed
artifacts cannot be accepted as fresh proof.

Use metamorphic cases: consistently rename a repository or symbol in the estate
and oracle and obtain equivalent results; change only a consumer identity or a
single causal edge and lose only the corresponding credit.

Differentially compare simple matching against a tiny independently written
reference checker on small examples. Inject targeted scorer faults, including
ignoring repo identity, ignoring consumers, trusting nonempty evidence, and
bypassing receipt or schema checks. Inject scenario faults, including restoring
an old topic or breaking a repair's field binding.

Protect acceptance-test files and oracle artifacts with hashes and an external
verifier process. Agents evaluating scenarios cannot read hidden expectations
or modify the tests, patches, scorer, or verifier. During implementation,
intentional test changes require explicit review; a new self-generated hash
does not approve a changed oracle.

**Exit gate:** every deliberately introduced fault listed above is detected by
the check designed for it. Record surviving faults and the remaining coverage
boundary; do not substitute line coverage for verification strength.

### Phase 4: integrate the runner and save independent evidence

Remove minimum-token and minimum-tool-call validity floors. Preserve actual
usage measurements and separately require that configured products were
available and successfully invoked. A successful call proves participation,
not the correctness of a result.

Remove REST-001-specific reasoning hints from the prompt. Give all cohorts the
same neutral task and diff. Keep hidden repairs, oracle tests, and result logs
outside the searchable estate, product indexes, and model context. Audit this
separation in runner tests. Known v1 scenarios are development cases; reserve
new v2 variants as holdouts after prompt/scorer development.

Retain product-first playbooks as an explicit treatment: the comparison measures
agent + product + frozen playbook, not unrestricted natural tool adoption.
Publish the playbook and adapter identity. Keep natural-adoption results separate.
Remove the mechanical symbol-anchor exclusion for literal/behavior changes:
represent these anchors explicitly, or report unsupported product capabilities
without silently dropping those scenarios from the common comparison.
Anchor extraction must use only the public task and proposed diff, never hidden
expected actions, paths, or repair patches.

Fix the model, revisions, task, file access, thinking policy, and maximum total
time/token budgets before running. Account for Phase A latency and all Phase B
usage; equal maximum budgets do not require identical actual consumption.

Save a verification bundle per attempt: criteria and oracle versions, estate and
patch hashes, commands, exit codes, assertions, raw failure logs, product
receipts, final answer, score components, and run status. Keep failures as well
as successes. Do not store credentials in receipts.

Separate setup failures, invalid output, valid incorrect answers, unavailable
products, and budget exhaustion in reporting. Product availability and its
effect on correctness are separate outcomes. A fresh blinded reviewer may
assess ambiguous semantic explanations using an anchored rubric; model judgment
cannot override a failing deterministic check or redefine expected behavior.

**Exit gate:** one full harness smoke per active product and one baseline smoke
has a complete verification bundle. Cheap, correct runs remain eligible;
fabricated or stale proof does not. These are integration checks, not rankings.

### Phase 5: run a bounded comparison and report the actual outcome

Update, 2026-10-18: the [broader discovery plan](2026-10-18-broader-discovery-plan.md)
replaces the following guided 60-run pilot as the proposed next tool-selection
milestone. The original phase remains below as planning history. New holdouts
and their verifier/scorer gates must pass before the broader campaign.

Once all earlier gates pass, run five repeats of each of the three verified
pilot scenarios for agent-only, Gortex-first, RepoWise-first, and Codebase
Memory-first: 60 fresh model runs. Freeze configuration before the cohort and
record a cost ceiling before launch. Do not add a new product mid-cohort.

Primary reporting: critical downstream-action misses, action precision/recall,
causal-edge correctness, and verified downstream impact. Report production edits,
validation-only obligations, and unaffected controls separately. Report
unresolved evidence and false positives rather than forcing binary certainty.

Secondary reporting: repository/symbol discovery, product candidate retrieval,
agent acceptance/rejection, file-search recovery, test recommendations, latency,
total tokens, monetary cost when available, failure rate, and setup effort.
Correctness and efficiency remain separate; retire the blended composite as
the tool-selection criterion. Verify eligibility in aggregation, not just in
the answer stamp, and never mix v1/v2 or differing harness cohorts.

Use paired scenario-level comparisons and repeat spread. The three-scenario
pilot tests operation and reveals failure modes; it cannot justify a general
winner or a stable statistical effect. Expand to balanced, executable REST,
Kafka, private-DB, and semantic-change holdouts only after those failure modes
are understood. Screen GitNexus or other new tools in a subsequent cohort.

**Exit gate:** a report can say which required actions each configuration found,
missed, or invented, with auditable evidence and cost. Broader claims wait for
the larger held-out corpus.

## Implementation boundaries and first deliverable

Use the saved `codex/restart-product-evaluation` implementation as the starting
point for a future implementation branch; current `main` is an older standalone
study revision. Carry this plan into that branch. Preserve historical reports
and pinned fixture repos. Persist new oracle tests separately from the estate
snapshot supplied to agents. Publish study changes under the monorepo's
`impact-study/` prefix when publication is requested.

Likely changes: `harness/schema/`, `harness/scoring/score.py` and its tests,
`harness/run/{run.ts,playbook.ts,aggregate.py}` and their tests, the neutral prompt,
a versioned v2 corpus, a small verifier command, and verification artifacts.
Reuse Python/Node, Maven/JUnit, Testcontainers, Compose, and Bruno already present.
Do not introduce a workflow framework or make an LLM judge the foundational oracle.

First deliverable: the reviewed acceptance matrix, one failing scorer regression
reproducing a known loophole, and one verified scenario with a protected
baseline/change/repair check. Complete that vertical slice before expanding
schema machinery, product adapters, or the corpus.
