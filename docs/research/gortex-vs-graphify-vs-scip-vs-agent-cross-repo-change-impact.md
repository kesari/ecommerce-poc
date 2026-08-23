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
