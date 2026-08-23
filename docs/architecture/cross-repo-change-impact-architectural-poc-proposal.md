# Cross-Repository Change Impact Intelligence
## Architectural POC Suggestion and Proposal

**Status:** Architecture POC proposal  
**Primary decision:** Determine whether a maintained code/system graph adds enough incremental change-impact recall and evidence quality to justify its operational complexity over compiler indexing plus agent-driven retrieval.  
**Primary use case:** Cross-repository change-impact analysis in a large Java/Spring microservice estate, including REST, event, schema, shared-library, and internal-code changes.

---

# 1. Executive Summary

The POC should not begin with the assumption that a knowledge graph is inherently the right architecture.

The working thesis is narrower:

> **Retrieval finds relevant knowledge.  
> Graphs encode dependency topology.  
> Compilers establish deterministic truth.  
> LLMs reason across all three.**

This proposal therefore evaluates four approaches to cross-repository change impact:

1. **Plain Claude Code/Codex retrieval** — dynamic exploration and semantic reasoning.
2. **SCIP/Sourcegraph-style precise indexing** — compiler-derived symbol truth and cross-repository code navigation.
3. **Gortex** — code and system topology, including cross-service contracts and blast-radius analysis.
4. **Graphify** — a lighter persistent graph used as a control to test how much a broad knowledge graph adds beyond retrieval.

The central question is not whether Gortex or Graphify can construct a graph.

The central question is:

> **Does graph traversal discover important dependencies that simpler retrieval and compiler indexing do not discover reliably enough?**

The Mem0 architecture change increases the burden of proof. Mem0's experience suggests that a broad graph can add latency, maintenance, and complexity without enough retrieval benefit when the underlying problem is semantic relevance rather than dependency topology.

That does **not** invalidate graph-based code analysis.

It suggests a stricter design rule:

> **Only create and persist a graph edge when traversal over that edge materially improves a dependency or topology question that retrieval cannot answer reliably.**

The POC should therefore treat **marginal value of the graph** as a first-class metric alongside edge precision, edge recall, provenance, latency, and operational cost.

The preferred end-state hypothesis is not:

```text
Gortex OR Sourcegraph OR Claude/Codex
```

It is:

```text
                        Agent
                    Claude / Codex
                          │
          ┌───────────────┼────────────────┐
          │               │                │
          ▼               ▼                ▼
      Retrieval       Code topology    System topology

 semantic search         SCIP            Gortex
 BM25                                     contracts
 entity matching                         REST
 docs / ADRs                              Kafka
 git history                              schemas
          │               │                │
          └───────────────┼────────────────┘
                          ▼
                   Evidence aggregation
                          │
                          ▼
                    Confidence model
                          │
                          ▼
                    Change contract
```

The POC exists to determine whether that architecture is justified by evidence.

---

# 2. Problem Statement

Modern coding agents are very strong at understanding code once they retrieve the right context.

They are weaker at proving that they have found **all** relevant dependencies across a large estate.

For cross-repository change impact, this distinction is critical.

An agent can:

```text
reason
   ↓
choose next search
   ↓
read results
   ↓
reason
   ↓
choose next search
```

But:

```text
recall depends on search decisions
```

The agent cannot directly know what it has not searched.

For production change-impact analysis, missing one critical downstream service may be more dangerous than returning several false positives.

The POC therefore optimizes first for:

> **low false-negative rate and high cross-repository recall**

rather than token savings or visually impressive graph exploration.

---

# 3. Architecture Principles

## 3.1 Use Retrieval for Relevance

Use:

```text
BM25
+
embeddings
+
entity matching
+
LLM reasoning
```

for information such as:

- documentation
- ADRs
- git history
- architectural intent
- business rules
- semantic context
- loosely related engineering knowledge

These are primarily relevance questions.

Persisting every semantic relationship as a graph edge is not automatically valuable.

---

## 3.2 Use Compiler-Derived Indexing for Deterministic Program Structure

Use SCIP/compiler-derived indexes for:

- definitions
- references
- implementations
- type relationships
- symbol identity
- compile-time dependencies
- precise cross-repository code navigation where supported

These edges have the highest confidence because they originate from compiler/indexer semantics.

Conceptually:

```text
Symbol A
   │
references
   ▼
Symbol B
```

Here, the graph is not merely an inferred abstraction.

> **The graph is the program structure.**

---

## 3.3 Use Graph Topology Only Where Traversal Has Genuine Value

A Gortex-like graph is most defensible for topology such as:

```text
METHOD_CALL
IMPLEMENTS
IMPORTS
HTTP_CONSUMES
HTTP_PROVIDES
PUBLISHES_TOPIC
CONSUMES_TOPIC
DEPENDS_ON
```

Examples:

```text
service A
   │
   │ POST /orders
   ▼
service B
```

and:

```text
service A
   │
   │ publishes
   ▼
orders.created
   │
   │ consumed by
   ▼
service B
```

These are actual compile-time or runtime relationships, not merely semantic similarity.

---

## 3.4 Do Not Build a Universal Enterprise Knowledge Graph by Default

Avoid using one graph as the universal representation for:

```text
 code ─────────────┐
 docs ─────────────┤
 ADRs ─────────────┤
 Jira ─────────────┤
 Slack ────────────┼──► GIANT GRAPH
 APIs ─────────────┤
 Kafka ────────────┤
 DB ───────────────┤
 ownership ────────┘
```

The POC should explicitly test whether a graph edge deserves to exist.

A useful rule is:

```text
Should this be a graph edge?

A imports B                   YES
A calls B                     YES
A implements B                YES
Service A calls endpoint B    YES
A publishes Kafka topic T     YES
B consumes Kafka topic T      YES

ADR mentions Payments         NO
README discusses Checkout     NO
Document similar to another   NO
Two classes conceptually
related                        PROBABLY NO
```

---

# 4. POC Decision Hypotheses

The POC should test the following hypotheses independently rather than treating "graph" as a single feature.

## H1 — Compiler Truth

**SCIP/Sourcegraph-style indexing will provide the highest-confidence symbol-level dependency information.**

Expected strengths:

- exact symbol references
- definitions
- implementations
- shared-library usage
- cross-repository symbol relationships where indexed

Expected weakness:

- runtime and integration boundaries that do not exist as language-level symbol edges

---

## H2 — System Topology

**Gortex will materially improve recall for cross-service dependencies that compiler indexes cannot represent directly.**

The strongest expected areas are:

- REST providers/consumers
- Kafka/event publishers and consumers
- gRPC
- OpenAPI
- cross-language consumers
- schemas and serialized contracts
- system-level blast radius

The key phrase is **materially improve**.

A small improvement is not sufficient if it requires substantial graph storage, daemon operation, indexing, schema maintenance, and troubleshooting.

---

## H3 — Agent Reasoning

**Claude Code/Codex will provide the strongest semantic and architectural reasoning but will not be sufficiently exhaustive by itself for authoritative impact enumeration.**

Expected strengths:

- docs and ADR interpretation
- business invariants
- configuration interpretation
- reasoning across code, tests, schemas, history, and documentation

Expected weakness:

- completeness cannot be inferred from agent search behavior alone

---

## H4 — Broad Knowledge Graph

**Graphify will demonstrate whether a simpler persistent knowledge graph adds measurable value beyond hybrid retrieval, but may become redundant if SCIP + Gortex + agent reasoning cover the required space.**

This is deliberately a control hypothesis.

Graphify should earn a place in the target architecture through measurable value, not because graph persistence is attractive in isolation.

---

## H5 — Combined Evidence

**A combined agent + retrieval + SCIP + system-topology model will outperform any individual component on evidence-backed cross-repository change impact.**

The desired result is not merely a list of "possibly related" files.

It is an evidence-backed **change contract**.

---

# 5. POC Contestants

Run the same ground-truth scenarios through the following modes.

## Baseline A — Agent + Native Search

```text
Claude Code / Codex
+
grep / ripgrep / file reads
+
normal repository exploration
```

Purpose:

- establish what a strong coding agent can do with no dedicated code graph
- measure search decisions, latency, tokens, recall, and evidence quality

---

## Baseline B — Agent + Hybrid Retrieval

```text
Claude Code / Codex
+
BM25
+
semantic retrieval
+
entity matching where useful
```

Purpose:

- test the Mem0-inspired simpler retrieval architecture
- establish whether semantic knowledge graphs add value beyond modern retrieval

---

## Baseline C — Agent + SCIP / Sourcegraph-Style Indexing

```text
Agent
+
SCIP/compiler index
```

Purpose:

- establish the strongest deterministic code-structure baseline
- identify what remains missing after compiler-derived cross-repository indexing

---

## Candidate D — Agent + Gortex

```text
Agent
+
Gortex
```

Purpose:

- test cross-service topology and blast-radius analysis
- measure incremental recall beyond native search and SCIP
- evaluate graph freshness, provenance, local overlays, and speculative impact

---

## Control E — Agent + Graphify

```text
Agent
+
Graphify
```

Purpose:

- evaluate a lighter persistent graph
- measure whether broad structural/inferred relationships provide enough incremental value to justify persistence

---

## Candidate F — Combined Stack

```text
Agent
+
Hybrid retrieval
+
SCIP
+
Gortex
```

Purpose:

- test the proposed end-state architecture
- determine whether evidence aggregation improves recall without creating unacceptable noise

---

# 6. POC Corpus and Ground Truth

Use approximately **30 historical production changes** where the actual blast radius can be reconstructed with high confidence.

Suggested composition:

```text
10 REST contract changes
5 Kafka schema changes
5 shared-library changes
5 DB/schema changes
5 internal refactors
```

For every historical change, establish a ground-truth record before running the contestants:

```text
Ground truth

Affected repositories
Affected symbols
Affected contracts
Required tests
Actual incidents/regressions
```

Where available, also capture:

- pull requests
- rollout notes
- test failures
- production incidents
- downstream fixes
- consumer repositories
- schema/version changes
- ownership boundaries
- feature flags/configuration involved

The ground-truth set must be frozen before comparing tools.

---

# 7. Representative Change Scenarios

The historical corpus should contain examples equivalent to the following.

## 7.1 Internal Java Refactor

Example:

```java
public record DeliveryAddress(
    String postcode,
    String country
) {}
```

Question:

> Who uses `DeliveryAddress` and what will fail if its Java-level API changes?

Expected strongest source:

```text
SCIP / compiler-derived index
```

---

## 7.2 Serialized Contract Change

Example:

```diff
public record PaymentRequest(
-   BigDecimal amount,
+   Money amount,
    String currency
) {}
```

Question:

> What breaks across repositories and runtime contracts if serialization changes?

Potential impact surface:

```text
PaymentRequest
      │
      ▼
POST /payments
      │
      ├──────────────────────────┐
      ▼                          ▼
checkout-service          refund-service
PaymentClient             PaymentClient
      │                          │
      ▼                          ▼
checkout tests             refund tests

         +

OpenAPI /payments
         │
         ▼
partner-payment-sdk

         +

Kafka payment.authorised
         │
         ▼
reconciliation-service
```

---

## 7.3 REST Endpoint Change

Example:

```text
POST /payments/authorisations
```

The POC should verify whether the tooling finds both obvious and non-obvious consumers such as:

```text
checkout
mobile-api
refund
fraud-orchestrator
legacy-checkout
partner-api
backoffice
payment-reconciliation
integration-test-suite
```

The important question is:

> Which dependencies are missed, not merely which are found?

---

## 7.4 Event / Kafka Change

Example:

```text
payment.authorised
```

Trace:

```text
publisher
   ↓
topic/schema
   ↓
all known consumers
   ↓
tests / processors / ETL
```

This is expected to be one of the clearest tests of whether system topology adds value beyond SCIP.

---

## 7.5 Architectural / Business Invariant

Example:

```java
if (market == Market.UK) {
    return legacyTaxCalculation(order);
}
```

with:

```text
# ADR-182

UK checkout remains on legacy tax calculation
until Finance completes migration.
```

and configuration such as:

```hcl
ENABLE_NEW_TAX_SERVICE = "false"
```

Question:

> Does the change violate or affect an architectural/business invariant not represented by static code references?

Expected strongest component:

```text
Claude/Codex + retrieval
```

---

# 8. Evaluation Metrics

The original evaluation criteria remain:

```text
edge precision
×
edge recall
×
provenance
```

Add:

```text
MARGINAL VALUE OF THE GRAPH
```

The core scorecard is:

| Metric | Why it matters |
|---|---|
| Repo recall | Did it find every affected repo? |
| Symbol recall | Did it find every affected symbol? |
| Contract recall | Did it see REST/events/etc.? |
| Precision | How much noise? |
| False-negative rate | **Most important** |
| Time to answer | Developer productivity |
| Tokens consumed | Agent efficiency |
| Evidence quality | Can a reviewer verify the result? |
| Fresh-change latency | Can it work during active development? |
| Setup/maintenance | Enterprise operational cost |
| Edge provenance | Is the relationship compiler-derived, extracted, inferred, or hypothesized? |
| Graph incremental value | What did graph traversal find that retrieval/SCIP did not? |
| Staleness behavior | How quickly and reliably does the index/graph reflect code changes? |
| Reviewer trust | Would engineers use the output to make a change decision? |

A weighted score proposed in the original analysis remains useful:

```text
Score =
  35% cross-repo recall
+ 20% contract recall
+ 15% precision
+ 10% evidence quality
+ 10% freshness
+  5% latency
+  5% operational cost
```

Do **not** optimize the POC around:

```text
50% token savings
```

Token reduction is useful, but it is secondary to change-impact correctness.

---

# 9. False-Negative Principle

Suppose the true impact contains 20 components.

### Tool A

```text
23 components
3 false positives
0 missing
```

### Tool B

```text
17 components
0 false positives
3 missing
```

For ordinary search, Tool B may feel cleaner.

For production change impact:

> **Tool A is safer.**

The POC should therefore explicitly classify misses by severity:

- critical runtime dependency missed
- contract consumer missed
- test suite missed
- low-risk informational dependency missed

A single false negative should be investigated to determine **why the relationship was invisible**.

---

# 10. Evidence and Confidence Model

The combined system should not flatten every result into one undifferentiated list.

Use evidence tiers.

## Tier 1 — Compiler Truth

```text
SCIP/compiler says:
A references B.
```

Confidence:

```text
VERY HIGH
```

---

## Tier 2 — Extracted / Structural Topology

```text
Gortex says:
service A POST /orders
matches
service B @PostMapping("/orders")
```

Confidence:

```text
HIGH
```

---

## Tier 3 — Semantic Inference

```text
Graph/LLM says:
this tax change probably affects
checkout business rules.
```

Confidence:

```text
MEDIUM
```

---

## Tier 4 — Hypothesis

```text
LLM thinks:
legacy-reconciliation may also care.
```

Confidence:

```text
INVESTIGATE
```

The POC should measure whether provenance survives all the way to the final response.

---

# 11. Desired Output: Evidence-Backed Change Contract

The POC should normalize results into a common output structure.

Example:

```text
Change:
PaymentRequest.amount: BigDecimal → Money

CERTAIN
  order-service
    Evidence: compiler reference
  checkout-service
    Evidence: generated client / compiler reference

HIGH CONFIDENCE
  fulfilment-service
    Evidence: POST /payments contract match
  reconciliation-service
    Evidence: consumes payment.authorised

POSSIBLE
  legacy-reporting
    Evidence: semantic/configuration relationship

Verification:
  17 unit/integration tests
  3 contract tests
  2 integration suites

Unknown / unresolved:
  1 dynamic endpoint construction
```

This is a better target than:

> "I inspected the code and this looks safe."

---

# 12. Gortex-Specific POC Checks

Gortex should be evaluated on more than its headline blast-radius command.

Specifically test:

## 12.1 Cross-Repo Contract Matching

Can it reliably connect:

```text
basket-service
      │
      │ POST /v1/orders
      ▼
http::POST::/v1/orders
      ▲
      │
@PostMapping("/v1/orders")
order-service
```

Measure:

- true matches
- false matches
- missed matches
- version/path normalization behavior
- cross-language behavior

---

## 12.2 Event Topology

Measure publisher/consumer discovery across:

- Kafka
- RabbitMQ if applicable
- other supported messaging mechanisms relevant to the estate

Check whether it resolves topic names constructed through constants, configuration, or wrappers.

---

## 12.3 Blast Radius

For a symbol or contract change:

```text
impact(OrderDto)
       │
       ▼
   graph traversal
       │
       ├─ callers
       ├─ implementations
       ├─ contracts
       ├─ service consumers
       ├─ tests
       └─ communities
```

Measure both:

- recall
- noise introduced by traversal depth

---

## 12.4 Overlay / Shadow Graph

Test unsaved/local modifications:

```text
main branch
     │
     ▼
base graph
     │
     ├─────────────────┐
     │                 │
 developer edit       developer edit
     │                 │
     ▼                 ▼
 overlay A          overlay B
```

Desired workflow:

```text
Agent proposes patch
        ↓
Gortex overlay
        ↓
recalculate graph
        ↓
broken callers
contract drift
affected tests
        ↓
Agent revises patch
```

Evaluate whether this provides meaningful pre-edit or pre-commit safety.

---

## 12.5 Graph Freshness

Measure:

- initial index correctness
- incremental update correctness
- deleted/renamed symbols
- branch changes
- local unsaved edits
- stale cross-repo edges

A correct graph that is frequently stale is not a dependable impact system.

---

# 13. Graphify-Specific POC Checks

Graphify should be evaluated primarily for **marginal value beyond retrieval**.

Its important distinction between:

```text
EXTRACTED
```

and:

```text
INFERRED
```

relationships should be preserved in outputs.

Example:

```text
OrderService
   |
   | CALLS
   | EXTRACTED
   v
PaymentClient
```

versus:

```text
Checkout
   |
   | RELATED_TO
   | INFERRED
   v
Payment
```

Questions to answer:

1. Which EXTRACTED edges provide capabilities not already covered by SCIP?
2. Which INFERRED edges provide useful signal beyond BM25 + semantic retrieval?
3. Do inferred relationships improve recall or mostly increase noise?
4. Is persisted graph maintenance justified by the incremental value?
5. Does Graphify remain useful as a lightweight developer-local graph even if it is not part of the enterprise change-control path?

---

# 14. SCIP / Sourcegraph-Specific POC Checks

SCIP is the deterministic baseline.

Test:

- definitions
- references
- implementations
- shared-library usage
- transitive/cross-repository navigation
- Java/Kotlin symbol identity where relevant
- generated clients if indexed
- dependency-version boundaries
- renamed symbols
- incomplete index coverage

The POC should explicitly record cases where:

```text
SCIP knows the code edge
```

but does **not** know the runtime/system edge.

Example:

```text
A.java
  ↓ serialized JSON
Kafka
  ↓
Python consumer
```

Those gaps define the addressable value for a Gortex-like system topology layer.

---

# 15. Agent-Only POC Checks

Claude Code/Codex should be allowed to use normal repository exploration and reasoning without graph assistance.

Measure:

- search breadth
- number of repositories opened
- number of files inspected
- token consumption
- time to evidence-backed answer
- reproducibility across repeated runs
- missed dependencies
- unsupported assertions
- ability to use ADR/config/history context

The POC should distinguish:

> **can discover**

from:

> **guaranteed to enumerate**

This is the primary reason agent-only retrieval should not automatically become the authoritative impact engine.

---

# 16. POC Execution Phases

No implementation should be treated as the winner before the ground-truth baseline exists.

## Phase A — Build Ground Truth

- select historical changes
- categorize change type
- reconstruct affected repos/symbols/contracts/tests
- freeze ground truth
- define severity of misses

## Phase B — Run Independent Contestants

Execute the same queries against:

- agent-only
- agent + hybrid retrieval
- agent + SCIP
- agent + Gortex
- agent + Graphify

Do not allow one contestant's results to influence another contestant's search.

## Phase C — Analyze Marginal Value

For every additional dependency found by Gortex or Graphify, label it:

```text
already found by agent?
already found by retrieval?
already found by SCIP?
unique graph discovery?
true positive?
false positive?
```

This is the most important phase for deciding whether persistent graph infrastructure is justified.

## Phase D — Test Combined Architecture

Run:

```text
Agent
+
Hybrid retrieval
+
SCIP
+
Gortex
```

Evaluate whether evidence aggregation improves results or merely combines noise.

## Phase E — Active Change Simulation

Use representative local modifications and test:

- index freshness
- overlay behavior
- speculative impact
- changed contract detection
- test recommendations
- evidence update after edits

## Phase F — Architecture Decision

Produce:

- capability matrix
- recall/precision results
- false-negative analysis
- cost/complexity analysis
- graph marginal-value analysis
- recommended target architecture
- explicit components to adopt, reject, or defer

---

# 17. Suggested Decision Gates

These gates should be agreed **before** looking at final POC results.

## Gate 1 — Deterministic Correctness

No candidate should replace SCIP/compiler indexing unless it can match its reliability on compiler-known symbol relationships.

The more likely architecture is complementary, not replacement.

---

## Gate 2 — Cross-Service Incremental Recall

Gortex should only progress if it demonstrates material incremental recall in dependencies such as:

- REST consumers/providers
- event publishers/consumers
- gRPC
- schemas
- generated clients
- cross-language consumers

that the strongest non-Gortex baseline misses.

---

## Gate 3 — False-Negative Reduction

The graph layer must reduce important false negatives, not just produce more results.

---

## Gate 4 — Precision / Trust

The additional recall cannot come from overwhelming engineers with so many false positives that results become routinely ignored.

---

## Gate 5 — Evidence Provenance

Every meaningful relationship should be classified as one of:

```text
compiler-derived
extracted
contract-matched
inferred
LLM-hypothesized
```

An answer without provenance should not be treated as authoritative.

---

## Gate 6 — Freshness

The solution must behave correctly against:

- current branch state
- recent cross-repo changes
- local modifications where supported

Stale topology is a safety risk.

---

## Gate 7 — Marginal Value vs Operational Cost

If the result is:

```text
Hybrid retrieval:
18/20 affected repos

Gortex:
19/20
```

and the additional graph infrastructure is expensive to run and maintain, adoption may not be justified.

If the result is closer to:

```text
Hybrid retrieval:
13/20

SCIP:
16/20

Gortex:
20/20
```

and the unique discoveries are genuine REST/Kafka/gRPC/OpenAPI/cross-language dependencies, then the graph has justified its existence.

---

# 18. Expected Outcomes Before Running the POC

These are hypotheses, not benchmark results.

## Internal Refactor

```text
SCIP          🥇
Gortex        🥈
Claude/Codex  🥉
Graphify
```

## Cross-Repo Java Dependency

```text
SCIP ≈ Gortex 🥇
```

## REST Contract Change

```text
Gortex        🥇
Claude/Codex  🥈
Graphify      🥉
SCIP
```

## Kafka/Event Change

```text
Gortex        🥇
Claude/Codex  🥈
Graphify
SCIP
```

## Architectural / Business Invariant

```text
Claude/Codex  🥇
Graphify      🥈
Gortex
SCIP
```

## Exhaustive Enterprise Blast Radius

Expected strongest architecture:

```text
SCIP
   +
Gortex
   +
Claude/Codex
```

with hybrid retrieval supplying semantic context.

---

# 19. Architectural Recommendation to Validate

The POC should validate this separation of concerns:

```text
                        Developer
                            │
                            ▼
                    Claude / Codex
                     Agent Reasoner
                            │
        ┌───────────────────┼────────────────────┐
        │                   │                    │
        ▼                   ▼                    ▼
 Semantic Retrieval     SCIP Index          Gortex Graph
 BM25 / embeddings      compiler truth      system topology
 entities / docs                            contracts/events
        │                   │                    │
        └───────────────────┼────────────────────┘
                            ▼
                     Impact Evidence
                            │
                  ┌─────────┴─────────┐
                  │                   │
            deterministic          inferred
                evidence            evidence
                  │                   │
                  └─────────┬─────────┘
                            ▼
                     Confidence Model
                            │
                            ▼
                     Change Contract
                            │
             ┌──────────────┼──────────────┐
             ▼              ▼              ▼
          tests        human review    agent action
```

This architecture deliberately refuses to make the graph responsible for every kind of knowledge.

---

# 20. Non-Goals

The POC is **not** intended to:

- build a universal enterprise knowledge graph
- replace the compiler or precise code-navigation index
- prove that graph databases are generally better than retrieval
- optimize primarily for token reduction
- automate production approval based on POC output
- treat inferred edges as equivalent to deterministic edges
- replace human review for critical changes

The POC is intended to answer one narrower question:

> **What is the minimum combination of retrieval, deterministic indexing, topology analysis, and LLM reasoning required to achieve trustworthy cross-repository change-impact analysis?**

---

# 21. Required POC Deliverables

The final POC output should include:

1. **Ground-truth corpus**
   - change categories
   - known affected repos/symbols/contracts/tests

2. **Per-contestant scorecard**
   - recall
   - precision
   - false negatives
   - evidence quality
   - freshness
   - latency
   - tokens
   - operational complexity

3. **Miss analysis**
   - why each important dependency was missed
   - whether the miss is fixable
   - whether the source relationship is deterministic, structural, or semantic

4. **Graph marginal-value report**
   - unique true positives attributable to Gortex
   - unique true positives attributable to Graphify
   - corresponding operational cost

5. **Evidence/provenance model**
   - compiler-derived
   - extracted
   - contract-matched
   - inferred
   - hypothesized

6. **Combined-stack assessment**
   - whether Agent + Retrieval + SCIP + Gortex materially beats every standalone option

7. **Architecture decision**
   - adopt
   - adopt with constraints
   - keep as developer-local tooling
   - defer
   - reject

8. **Example change contract**
   - a complete evidence-backed output for at least one representative production change

---

# 22. POC Decision Statement

The POC should end with a decision in this form:

```text
SCIP:
  Adopt / Do not adopt
  Reason:
  Deterministic symbol-level value

Gortex:
  Adopt / Adopt with constraints / Defer / Reject
  Reason:
  Measured incremental cross-service recall vs operational cost

Graphify:
  Adopt / Developer-local only / Defer / Reject
  Reason:
  Measured marginal value beyond hybrid retrieval

Hybrid retrieval:
  Adopt / Do not adopt
  Reason:
  Semantic-context quality and cost

Claude/Codex:
  Role:
  Reasoner and orchestrator, not sole source of dependency truth

Target architecture:
  [selected components]

Known blind spots:
  [measured gaps]

Required safeguards:
  [confidence/provenance/human-review rules]
```

---

# 23. Core Decision Principle

The proposal can be summarized in four lines:

> **Retrieval finds relevant knowledge.**  
> **Graphs encode dependency topology.**  
> **Compilers establish deterministic truth.**  
> **LLMs reason across all three.**

The POC should prove where each boundary belongs rather than assuming it.

---

# Part II — Detailed Technical Analysis

The following detailed analysis is preserved from the source material so that the architectural rationale, examples, comparison scores, edge cases, Mem0 lessons, and original POC recommendation remain available in full.

The standalone Mem0 appendix supplied alongside the comparison is substantively identical to the Mem0 appendix already contained in the comparison document; literal duplication is removed here, while all unique substantive content is retained.

---

# Gortex vs Graphify vs SCIP/Sourcegraph vs Claude Code/Codex for Cross-Repo Change Impact

Looking specifically at **cross-repository change impact**, these tools fall into two fundamentally different families:

> **SCIP/Sourcegraph tells you what is statically true.**  
> **Gortex tries to tell you what will be affected.**  
> **Graphify gives an agent a graph from which it can reason about what may be affected.**  
> **Claude Code/Codex discovers and reasons about impact dynamically while doing the task.**

That distinction is more important than raw feature count.

---

## Principal-Engineer Ranking

For a large Java/Spring microservice estate, I would currently rate them like this. These are architectural assessments, not vendor benchmark results.

| Dimension | Gortex | Graphify | SCIP / Sourcegraph | Plain Claude/Codex |
|---|---:|---:|---:|---:|
| Exact symbol references | 8/10 | 6/10 | **10/10** | 6/10 |
| Cross-repo symbol resolution | **9/10** | 7/10 | **10/10** | 6/10 |
| REST/API relationship detection | **9/10** | 6/10 | 3/10 | 7/10 |
| Kafka/event relationship detection | **9/10** | 5/10 | 2/10 | 7/10 |
| Semantic architecture reasoning | 8/10 | 8/10 | 3/10 | **10/10** |
| Blast-radius query | **10/10** | 7/10 | 6/10 | 6/10 |
| Completeness confidence | 7/10 | 5/10 | **10/10*** | 5/10 |
| Explainability/evidence | **9/10** | **9/10** | 9/10 | 6/10 |
| Fresh local changes | **10/10** | 6/10 | 5–8/10 | **10/10** |
| Zero-setup usefulness | 6/10 | 8/10 | 4/10 | **10/10** |
| Enterprise-scale maturity | 4–5/10 | 4/10 | **10/10** | 8/10 |

\*Where SCIP coverage exists. Sourcegraph falls back to heuristic search/code navigation when precise indexes are not available.

A key caveat: **Gortex's technical proposition is excellent; that does not automatically mean it belongs in a critical enterprise change-control path today.**

---

## 1. SCIP/Sourcegraph Is the Strongest Baseline

Suppose this lives in `checkout-api`:

```java
public record DeliveryAddress(
    String postcode,
    String country
) {}
```

and it is exported from a shared dependency used by:

```text
basket-service
checkout-service
order-service
fulfilment-service
payment-service
```

SCIP has a major advantage:

```text
Java compiler / semantic indexer
             │
             ▼
           SCIP
             │
       symbol identity
             │
     ┌───────┼────────┐
     ▼       ▼        ▼
 definition refs   implementations
                     │
                     ▼
                 other repos
```

It is not guessing that two things named `DeliveryAddress` are the same symbol.

The language-specific indexer has compiler-level knowledge.

So if I ask:

> "Who uses `DeliveryAddress`?"

SCIP/Sourcegraph is the result I would trust most.

But now change the question:

> "What breaks if I rename JSON field `postcode` to `postalCode`?"

And SCIP becomes less sufficient.

Because the actual dependency graph may be:

```text
DeliveryAddress.java
       │
       │ Jackson
       ▼
JSON contract
       │
       ├────────────── REST ───────────► mobile checkout
       │
       ├────────────── REST ───────────► web checkout
       │
       └────────────── Kafka ──────────► fulfilment
                                           │
                                           ▼
                                      Snowflake ETL
```

These are not necessarily Java symbol references.

SCIP gives you an extraordinarily good **code graph**.

It is not inherently a full **system dependency graph**.

That is the opening Gortex is trying to exploit.

---

# 2. Gortex Moves From Code Graph → System Graph

Gortex explicitly models things such as:

- HTTP providers and consumers
- gRPC
- GraphQL
- Kafka/RabbitMQ/NATS/Redis pub-sub
- WebSockets
- OpenAPI
- environment variables
- Temporal workflows

and canonicalizes contracts so consumers in one repository can be matched against providers in another.

For example:

```text
repo: basket-service

CheckoutClient.java
      |
      | POST /v1/orders
      v

           GORTEX CONTRACT
       http::POST::/v1/orders

      ^
      |
@PostMapping("/v1/orders")
repo: order-service
```

That is qualitatively different from:

```text
find references(OrderController)
```

Gortex is trying to infer the architectural edge:

```text
basket-service
      │
      │ HTTP
      ▼
order-service
```

even though there is **no language symbol linking those repositories**.

This is why Gortex is considerably more interesting than Graphify for large microservice estates.

---

# 3. Gortex Has Turned Blast Radius Into a Primitive

Ordinarily an agent does something like:

```text
User:
What breaks if I change OrderDto?

Agent:

grep OrderDto
   ↓
read 8 files
   ↓
grep endpoint
   ↓
read client
   ↓
search other repo
   ↓
search Kafka topics
   ↓
inspect tests
   ↓
reason
```

Gortex wants that to become:

```text
impact(OrderDto)
       │
       ▼
   graph traversal
       │
       ├─ callers
       ├─ implementations
       ├─ contracts
       ├─ service consumers
       ├─ tests
       └─ communities
```

It also precomputes reachability information specifically to accelerate blast-radius calculations.

That is significantly more sophisticated than simply "give the LLM some context."

---

# 4. Graphify Sits Somewhere in Between

Graphify's architecture is much simpler and, in some ways, cleaner.

It produces:

```text
source code
docs
ADRs
configs
SQL
       │
       ▼
   Graphify extraction
       │
       ▼
     graph.json
       │
 ┌─────┼─────────────┐
 ▼     ▼             ▼
calls imports      concepts
       │
       ▼
   communities
```

Graphify resolves calls/imports/inheritance using tree-sitter and makes a useful design choice: graph edges distinguish **EXTRACTED** information from **INFERRED** relationships.

For example:

```text
OrderService
   |
   | CALLS
   | EXTRACTED
   v
PaymentClient
```

versus:

```text
Checkout
   |
   | RELATED_TO
   | INFERRED
   v
Payment
```

That provenance is extremely useful.

But Graphify is primarily:

> **persistent structural context for an LLM**

rather than a dedicated program-analysis/change-impact engine.

Gortex is much more opinionated around the actual software-engineering operation:

> change X → calculate affected surface → identify risk → suggest verification.

---

# 5. Plain Claude Code/Codex Has the Opposite Strength

Now consider this dependency:

```java
if (market == Market.UK) {
    return legacyTaxCalculation(order);
}
```

with this comment in another repository:

```text
# ADR-182

UK checkout remains on legacy tax calculation
until Finance completes migration.
```

and perhaps this Terraform configuration:

```hcl
ENABLE_NEW_TAX_SERVICE = "false"
```

A static code graph may struggle to understand the *meaning* of that relationship.

Claude/Codex can reason:

```text
code
+
tests
+
docs
+
git history
+
configs
+
schemas
+
build output
          │
          ▼
      LLM reasoning
          │
          ▼
"Changing this may affect the
 UK migration invariant."
```

That is their superpower.

But there is an enormous difference between:

> **can discover**

and

> **guaranteed to enumerate**.

---

# 6. Where Plain Agent Retrieval Loses for Impact Analysis

Imagine you have 120 repositories.

You change:

```text
POST /payments/authorisations
```

Claude/Codex could discover:

```text
payment-api
    ↓
checkout
    ↓
mobile-api
    ↓
refund
```

But how do you know there is not also:

```text
fraud-orchestrator
legacy-checkout
partner-api
backoffice
payment-reconciliation
integration-test-suite
```

?

The agent does not know what it has not searched.

This is the fundamental problem with using agent exploration for **exhaustive dependency discovery**.

Its algorithm is approximately:

```text
reason
   ↓
choose next search
   ↓
read results
   ↓
reason
   ↓
choose next search
```

So:

```text
recall depends on search decisions
```

Whereas an index can operate as:

```text
index everything
     ↓
resolve relationships
     ↓
query complete known graph
```

The first is excellent for **understanding**.

The second is better for **enumeration**.

---

# 7. The Critical Distinction: False Positives vs False Negatives

For blast-radius analysis, these systems should be judged differently than normal search.

Suppose the correct answer contains 20 affected components.

### Tool A

Returns:

```text
23 components
3 false positives
0 missing
```

### Tool B

Returns:

```text
17 components
0 false positives
3 missing
```

For normal code search, B might feel cleaner.

For production change impact, **A is much safer**.

The most dangerous metric here is not precision.

It is:

> **false-negative rate**

If `payment-reconciliation` does not appear in the blast radius and consequently is not tested, that omission can create a production incident.

This is why I would be reluctant to let an LLM alone be the authoritative impact engine.

---

# 8. SCIP Has the Opposite Problem

SCIP gives exceptionally high confidence for relationships it knows about.

Suppose:

```text
A.java
  ↓ compiler relation
B.java
```

You can trust that edge.

But:

```text
A.java
  ↓ serialized JSON
Kafka
  ↓
Python consumer
```

is not necessarily represented as the same kind of semantic code edge.

Therefore:

```text
SCIP

precision ██████████
semantic breadth █████
```

whereas Gortex is aiming for:

```text
Gortex

precision ████████
semantic breadth █████████
```

Graphify roughly:

```text
Graphify

precision ███████
semantic breadth ████████
```

and agent-only retrieval:

```text
Claude/Codex

reasoning ██████████
exhaustiveness ██████
```

Those are not measured scores; they describe the architectural shape.

---

# 9. Gortex's Overlay / Shadow-Graph Model Is Particularly Interesting

Gortex supports overlay/shadow graphs for unsaved editor state.

Meaning:

```text
main branch
     │
     ▼
base graph
     │
     ├─────────────────┐
     │                 │
 developer edit       developer edit
     │                 │
     ▼                 ▼
 overlay A          overlay B
```

Queries can operate on hypothetical graph states without changing the base graph.

That enables a workflow like:

```text
Agent proposes patch
        ↓
Gortex overlay
        ↓
recalculate graph
        ↓
3 broken callers
1 contract drift
7 affected tests
        ↓
Agent revises patch
```

This is much stronger than:

```text
LLM:
"I think this refactoring is safe."
```

---

# 10. I Would Not Replace SCIP With Gortex

The architecture I would prefer is:

```text
                 ┌──────────────────────┐
                 │       Agent          │
                 │ Claude / Codex       │
                 └──────────┬───────────┘
                            │
             reasoning / orchestration
                            │
            ┌───────────────┼──────────────┐
            ▼               ▼              ▼
     SCIP / compiler      Gortex       repo knowledge
        truth              graph        docs / ADR
            │               │              │
            └───────────────┼──────────────┘
                            ▼
                       Impact model
                            │
                 ┌──────────┴──────────┐
                 ▼                     ▼
              tests                 humans
```

I want multiple classes of evidence.

### Tier 1 — Compiler Truth

```text
SCIP/compiler says:
A references B.
```

Confidence:

```text
VERY HIGH
```

### Tier 2 — Structural Inference

```text
Gortex says:
service A POST /orders
matches
service B @PostMapping("/orders")
```

Confidence:

```text
HIGH
```

### Tier 3 — Semantic Inference

```text
Graph/LLM says:
this tax change probably affects
checkout business rules.
```

Confidence:

```text
MEDIUM
```

### Tier 4 — Hypothesis

```text
LLM thinks:
legacy-reconciliation may also care.
```

Confidence:

```text
INVESTIGATE
```

That hierarchy is critical.

---

# 11. Graphify Becomes Somewhat Redundant in a Mature Architecture

Once you have:

```text
SCIP
+
Gortex
+
Claude/Codex
```

Graphify becomes less essential.

Graphify's strengths are:

- simple
- portable
- transparent
- graph persistence
- good visualization
- docs + code graph
- relatively easy adoption

So it is very useful for an initial experiment.

But for a mature enterprise platform:

```text
                  Need
                   │
        ┌──────────┼───────────┐
        ▼          ▼           ▼
 Compiler truth System graph Semantic reasoning
        │          │           │
       SCIP      Gortex     Claude/Codex
```

covers the space more cleanly.

---

# 12. Example: Java Microservice API Change

Developer proposes:

```diff
public record PaymentRequest(
-   BigDecimal amount,
+   Money amount,
    String currency
) {}
```

## Plain Claude/Codex

It may do:

```text
grep PaymentRequest
        ↓
find controller
        ↓
find service
        ↓
find tests
        ↓
search clients
        ↓
inspect OpenAPI
        ↓
reason about serialization
```

Result:

> Likely impact: payment API, basket client, checkout tests.

Useful.

But completeness is uncertain.

---

## Graphify

Graph traversal might yield:

```text
PaymentRequest
     │
     ├─ used_by → PaymentController
     │
     ├─ used_by → PaymentService
     │
     ├─ referenced_by → OpenAPI
     │
     └─ connected_to → Checkout
```

Better structural coverage.

---

## SCIP

SCIP could precisely enumerate:

```text
PaymentRequest
      │
      ├─ PaymentController
      ├─ PaymentMapper
      ├─ PaymentValidator
      ├─ PaymentRequestTest
      └─ shared SDK consumers
```

Highest confidence for actual symbols.

But it may still miss runtime/API semantics.

---

## Gortex

Potential graph:

```text
PaymentRequest
      │
      ▼
POST /payments
      │
      ├──────────────────────────┐
      ▼                          ▼
checkout-service          refund-service
PaymentClient             PaymentClient
      │                          │
      ▼                          ▼
checkout tests             refund tests

         +

OpenAPI /payments
         │
         ▼
partner-payment-sdk

         +

Kafka payment.authorised
         │
         ▼
reconciliation-service
```

Then:

```text
blast_radius(PaymentRequest)

HIGH

5 repos
3 API consumers
1 event contract
17 callers
22 tests
2 external surfaces
```

That is the product vision.

---

# 13. The Biggest Question I Would Test With Gortex

Not performance.

Not token reduction.

Not even language coverage.

I would test:

> **Does its graph preserve enough semantic correctness that engineers do not learn to distrust it?**

Because there is a long history of code-analysis products failing here.

If engineers repeatedly see:

```text
Impact:
52 services
```

and 40 are noise, they will stop using it.

Likewise, if it says:

```text
Impact:
3 services
```

and production later reveals a fourth consumer, its role as a safety mechanism becomes questionable.

The entire product hinges on:

```text
edge precision
×
edge recall
×
provenance
```

Not graph visualization.

---

# 14. Evaluation Should Be Built Around Ground Truth

Take about **30 historical production changes** where the actual blast radius is already known.

For example:

```text
10 REST contract changes
5 Kafka schema changes
5 shared-library changes
5 DB/schema changes
5 internal refactors
```

For each change, establish:

```text
Ground truth

Affected repositories
Affected symbols
Affected contracts
Required tests
Actual incidents/regressions
```

Then run all four approaches blindly.

Measure:

| Metric | Why it matters |
|---|---|
| Repo recall | Did it find every affected repo? |
| Symbol recall | Did it find every affected symbol? |
| Contract recall | Did it see REST/events/etc.? |
| Precision | How much noise? |
| False-negative rate | **Most important** |
| Time to answer | Developer productivity |
| Tokens consumed | Agent efficiency |
| Evidence quality | Can reviewer verify it? |
| Fresh-change latency | Can it work during active development? |
| Setup/maintenance | Enterprise operational cost |

I would put **false-negative repo recall** above everything else.

A possible weighted score:

```text
Score =
  35% cross-repo recall
+ 20% contract recall
+ 15% precision
+ 10% evidence quality
+ 10% freshness
+  5% latency
+  5% operational cost
```

Not:

```text
50% token savings
```

Token reduction is economically useful but secondary.

---

# 15. Expected Outcome

Before running that benchmark, my hypothesis would be:

## Internal Refactor

```text
SCIP          🥇
Gortex        🥈
Claude/Codex  🥉
Graphify
```

## Cross-Repo Java Dependency

```text
SCIP ≈ Gortex 🥇
```

## REST Contract Change

```text
Gortex        🥇
Claude/Codex  🥈
Graphify      🥉
SCIP
```

## Kafka/Event Change

```text
Gortex        🥇
Claude/Codex  🥈
Graphify
SCIP
```

## Architectural / Business Invariant

```text
Claude/Codex  🥇
Graphify      🥈
Gortex
SCIP
```

## Exhaustive Enterprise Blast Radius

My expectation:

```text
SCIP
   +
Gortex
   +
Claude/Codex
```

beats any single system.

---

# Recommended Architecture

For an enterprise agentic engineering platform, I would not frame the decision as:

> **Gortex OR Sourcegraph OR Codex**

The LLM should be the **reasoner**, not the source of truth.

```text
                     Developer
                         │
                         ▼
                 Claude / Codex
                  Agent Reasoner
                         │
        ┌────────────────┼────────────────┐
        │                │                │
        ▼                ▼                ▼
   SCIP Index        Gortex Graph      Git / Docs
 compiler truth     system topology     ADR / tests
        │                │                │
        └────────────────┼────────────────┘
                         │
                         ▼
                  Impact Evidence
                         │
                  ┌──────┴──────┐
                  │             │
            deterministic     inferred
                edges           edges
                  │             │
                  └──────┬──────┘
                         ▼
                  Confidence model
                         │
                         ▼
                  Change contract

             "Changing X affects:

              CERTAIN
                order-service
                checkout-service

              HIGH CONFIDENCE
                fulfilment-service

              POSSIBLE
                reconciliation

              Verification:
                17 tests
                3 contract tests
                2 integration suites"
```

That is much closer to what a principal architect should want: not an AI saying:

> "I inspected the code and this looks safe."

but an AI assembling an **evidence-backed change contract** from multiple analysis systems.

---

# Appendix: What Mem0's Graph Removal Means for Gortex, Graphify, and Code-Impact Analysis

Mem0's recent architecture change is a useful caution against the assumption that:

> **knowledge graph = better reasoning**

The important distinction is between:

- **memory graphs for semantic recall**
- **code dependency graphs for deterministic impact analysis**

Mem0's experience is evidence against the former much more than the latter.

---

## 1. What Mem0 Actually Changed

Mem0 removed its external graph-memory subsystem from the newer OSS architecture, including graph-store integrations such as:

- Neo4j
- Memgraph
- Kuzu
- AGE
- Neptune

It replaced that path with a simpler hybrid retrieval approach based on:

```text
semantic search
+
BM25
+
entity matching/linking
```

The important architectural lesson is not:

> "Graphs are useless."

It is closer to:

> **Do not maintain a heavyweight graph unless graph traversal provides measurable value beyond simpler retrieval signals.**

Mem0's hosted platform still uses entity relationships as retrieval signals, but those relationships are no longer exposed as a separate external graph-store workflow.

---

# 2. Memory Graphs and Code Graphs Solve Different Problems

| Mem0-style memory graph | Gortex/SCIP-style code graph |
|---|---|
| Relationships are often inferred from natural language | Many relationships can be mechanically extracted |
| `"Raju works with Alice"` | `A.java → calls → B.java` |
| Edge may be probabilistic | Symbol edge can be deterministic |
| Primarily a retrieval problem | Primarily a program-analysis problem |
| Main question: "What memory is relevant?" | Main question: "What depends on this?" |
| Vector/entity retrieval may outperform traversal | Traversal can be the operation actually required |

For Mem0, imagine:

```text
User
 ├── works_at → Acme
 ├── likes → Fuji
 ├── owns → X-T30
 └── colleague → Alice
```

If the user asks:

> "What camera did I say I preferred?"

a vector/BM25/entity retrieval pipeline can often retrieve the relevant memory directly.

Doing this:

```text
query
 ↓
extract entities
 ↓
graph lookup
 ↓
traverse relationships
 ↓
rank nodes
 ↓
fetch associated memories
```

may simply add complexity and latency without improving the answer.

That is where Mem0's decision makes sense.

---

# 3. Code Impact Is Different Because Similarity Is Not Dependency

Consider:

```text
PaymentController
       │
       │ calls
       ▼
PaymentService
       │
       │ calls
       ▼
PaymentClient
       │
       │ POST /authorisations
       ▼
payment-service
       │
       │ publishes
       ▼
payment.authorised
       │
       ▼
reconciliation-service
```

Now the question is:

> "What is downstream of `PaymentController`?"

That is almost literally a graph traversal problem:

```text
reachable(PaymentController)
```

A vector database can retrieve things semantically similar to `PaymentController`, but similarity is not dependency.

The key distinction is:

```text
SIMILARITY != DEPENDENCY
```

A vector search might rank:

```text
PaymentControllerTest
PaymentConfiguration
PaymentResponse
RefundController
```

very highly.

But the actual blast radius may be:

```text
checkout-service
payment-service
fraud-service
reconciliation-service
```

Those components do not necessarily have high textual similarity.

That is why code-impact analysis still has a legitimate need for graph-like topology.

---

# 4. Mem0's Decision Makes Me More Skeptical of Graphify Than SCIP

Graphify builds a broad knowledge graph across things such as:

```text
code
docs
ADRs
configs
SQL
concepts
communities
relationships
```

Some of these relationships are useful.

But many inferred edges may be alternate representations of information an LLM plus hybrid search could recover more cheaply.

For example:

```text
Checkout
   │
 RELATED_TO
   │
   ▼
Payment
```

If that relationship is inferred mainly from text, the question becomes:

> Why persist this as a graph?

A simpler stack may already provide most of the value:

```text
BM25
+
embeddings
+
entity matching
+
LLM reasoning
```

Persisting a broad graph introduces costs:

- graph construction
- incremental updates
- stale edges
- edge-quality problems
- traversal latency
- storage
- schema evolution
- entity-resolution complexity

That is exactly the kind of architectural burden Mem0's redesign warns us about.

---

# 5. Gortex Has a Stronger Defense

Gortex has a stronger reason to maintain graph structure because many of its relationships correspond to actual software topology:

```text
METHOD_CALL
IMPLEMENTS
IMPORTS
HTTP_CONSUMES
HTTP_PROVIDES
PUBLISHES_TOPIC
CONSUMES_TOPIC
DEPENDS_ON
```

Those edges answer questions that retrieval alone cannot reliably answer exhaustively.

For example:

```text
service A
   │
   │ POST /orders
   ▼
service B
```

or:

```text
service A
   │
   │ publishes
   ▼
orders.created
   │
   │ consumed by
   ▼
service B
```

These are not just semantically related concepts.

They represent runtime or compile-time dependency.

That gives graph traversal genuine value.

---

# 6. SCIP Has the Strongest Graph Justification

SCIP has an even stronger argument because the relationships originate from compiler/indexer knowledge.

For example:

```text
Symbol A
   │
references
   ▼
Symbol B
```

The graph is not an AI abstraction imposed on the code.

> **The graph is the program structure.**

This is why SCIP remains the highest-confidence source for symbol-level dependency.

---

# 7. Avoid the "Giant Enterprise Knowledge Graph" Trap

A tempting architecture is:

```text
                 BAD DIRECTION

 code ─────────────┐
 docs ─────────────┤
 ADRs ─────────────┤
 Jira ─────────────┤
 Slack ────────────┼──► GIANT GRAPH
 APIs ─────────────┤
 Kafka ────────────┤
 DB ───────────────┤
 ownership ────────┘
```

This looks elegant because everything has one representation.

But it risks becoming an expensive universal abstraction that adds little value over simpler retrieval techniques for many information types.

The better separation is:

```text
                        Agent
                    Claude / Codex
                          │
          ┌───────────────┼────────────────┐
          │               │                │
          ▼               ▼                ▼
      Retrieval       Code topology    System topology

 semantic + BM25        SCIP           contracts
 entity matching                        REST
 docs / ADRs                           Kafka
 git history                           schemas
          │               │                │
          │               └───────┬────────┘
          │                       │
          └───────────────────────┤
                                  ▼
                            Agent reasoning
```

This keeps each representation aligned with the type of problem it solves best.

---

# 8. A Strong Design Rule

A useful architectural rule is:

> **Only create a graph edge when graph traversal gives us something materially better than retrieval.**

For example:

```text
Should this be a graph edge?

A imports B                   YES
A calls B                     YES
A implements B                YES
Service A calls endpoint B    YES
A publishes Kafka topic T     YES
B consumes Kafka topic T      YES

ADR mentions Payments         NO
README discusses Checkout     NO
Document similar to another   NO
Two classes conceptually
related                        PROBABLY NO
```

The lower half is generally better handled by retrieval.

---

# 9. This Changes How Gortex Should Be Evaluated

The earlier evaluation criteria were:

```text
edge precision
×
edge recall
×
provenance
```

Mem0's decision suggests adding another important metric:

```text
MARGINAL VALUE OF THE GRAPH
```

For each cross-repo impact question, compare:

```text
Agent + ripgrep/search
Agent + hybrid retrieval
Agent + SCIP
Agent + Gortex
```

Then ask:

> **Did the graph discover dependencies that the simpler retrieval system did not?**

Example:

```text
Hybrid retrieval:
18/20 affected repos

Gortex:
19/20
```

If Gortex needs:

- a daemon
- graph storage
- incremental indexing
- graph maintenance
- operational support

for only a marginal recall improvement, the graph may not justify itself.

But consider:

```text
Hybrid retrieval:
13/20

SCIP:
16/20

Gortex:
20/20
```

and the additional discoveries are:

```text
REST consumers
Kafka consumers
gRPC dependencies
OpenAPI clients
cross-language services
```

Then the graph has justified its existence.

---

# 10. Revised Architecture

The architecture I would now recommend is slightly simpler than the earlier proposal.

```text
                        Agent
                    Claude / Codex
                          │
          ┌───────────────┼────────────────┐
          │               │                │
          ▼               ▼                ▼
      Retrieval       Code topology    System topology

 semantic search         SCIP            Gortex
 BM25                                     contracts
 entity matching                         REST
 docs / ADRs                              Kafka
 git history                              schemas
          │               │                │
          └───────────────┼────────────────┘
                          ▼
                   Evidence aggregation
                          │
                          ▼
                    Confidence model
                          │
                          ▼
                    Change contract
```

This gives us three distinct capabilities.

## Relevance

Use:

```text
BM25
+
embeddings
+
entity matching
+
LLM reasoning
```

Best for:

- docs
- ADRs
- git history
- architectural intent
- business rules
- semantic context

---

## Deterministic Code Structure

Use:

```text
SCIP / compiler index
```

Best for:

- symbol references
- definitions
- implementations
- call relationships
- type relationships
- compile-time dependency

---

## Cross-Service Topology

Use:

```text
Gortex-like contract graph
```

Best for:

- REST providers/consumers
- Kafka publishers/consumers
- gRPC relationships
- OpenAPI contracts
- cross-language service dependencies
- system-level blast radius

---

# 11. The Broader Lesson From Mem0

Mem0's redesign strengthens the burden of proof for any architecture that introduces a graph.

The question should not be:

> "Can this information be represented as a graph?"

Almost anything can.

The correct question is:

> **Does graph traversal unlock an important query that simpler retrieval cannot answer reliably?**

For semantic recall, the answer may often be **no**.

For dependency topology, the answer can very plausibly be **yes**.

Therefore:

> **Use graphs for topology, not for relevance.**

For relevance:

```text
BM25 + embeddings + entity matching + LLM
```

For deterministic program structure:

```text
SCIP / compiler index
```

For cross-service topology:

```text
Gortex-like contract graph
```

And let Claude/Codex sit above those systems and reason across all three.

---

# 12. Impact on the Gortex vs Graphify Evaluation

Mem0's architectural shift does **not** invalidate Gortex or Graphify.

But it changes the burden of proof.

### Graphify

The question becomes:

> How much of Graphify's graph actually provides value beyond hybrid retrieval?

This needs to be demonstrated empirically.

### Gortex

The stronger question is:

> Does Gortex materially improve cross-service dependency recall over SCIP plus agent-based retrieval?

If yes, particularly across:

- REST
- Kafka
- gRPC
- schema boundaries
- cross-language consumers

then the graph has clear architectural value.

### SCIP

SCIP is least affected by this argument because its graph represents compiler-derived program structure rather than broad semantic relationships.

---

# Final Principle

A useful design principle for an enterprise agentic engineering platform is:

> **Retrieval finds relevant knowledge.  
> Graphs encode dependency topology.  
> Compilers establish deterministic truth.  
> LLMs reason across all three.**

That separation is likely more robust than attempting to encode the entire engineering organization into one universal knowledge graph.

---

# POC Recommendation

For an initial proof of concept, use three primary contestants:

1. **SCIP baseline**
2. **Gortex**
3. **Agent-only Claude Code/Codex**

Use **Graphify as the lightweight control**.

The key question is:

> If Gortex cannot materially beat SCIP + intelligent agent exploration on cross-service recall, is there enough justification for the additional graph layer?

If it **can**, particularly on REST, Kafka, schema, and other cross-repository boundaries, then it becomes a genuinely interesting building block for an enterprise agentic engineering platform.

---

## References

- Gortex: https://github.com/zzet/gortex
- Graphify: https://github.com/Graphify-Labs/graphify
- Sourcegraph precise code navigation: https://sourcegraph.com/docs/code-navigation/precise-code-navigation
- Sourcegraph SCIP overview: https://sourcegraph.com/docs/getting-started/github-vs-sourcegraph
- Anthropic Claude Code CLI usage: https://docs.anthropic.com/en/docs/claude-code/cli-usage
- OpenAI Harness Engineering: https://openai.com/index/harness-engineering/
- Mem0 OSS repository: https://github.com/mem0ai/mem0
- Mem0 migration documentation: https://docs.mem0.ai/migration/oss-v2-to-v3
