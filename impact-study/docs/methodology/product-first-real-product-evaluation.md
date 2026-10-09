# Real-product, product-first evaluation

Status: current methodology. This supersedes the original synthetic-index comparison and the natural-adoption pilot for product-quality claims.

The [2026-10-09 checkpoint and restart plan](2026-10-09-checkpoint-and-restart.md)
records what has actually run, the current verification results, and the next
evaluation milestone. Gate 0 completion does not imply scored evaluation
completion.

## Question

Can an open-source product improve an agent's identification of cross-repository change impact on the pinned commerce estate?

The product must be the real released software, run over the real ten-repository estate. A harness-built approximation cannot answer that question.

## Current matrix

| Cohort | Purpose | Default status |
|---|---|---|
| agent-only | Control: restricted file search and reasoning | Active |
| Gortex-first | Existing system/contract graph candidate | Active |
| RepoWise-first | Workspace structure, symbol context, and blast-radius candidate | Active after Gate 0 |
| Codebase Memory-first | Structural graph and `CROSS_*` edge candidate | Active after Gate 0 |
| SCIP and Graphify cohorts | Historical evidence only | Inactive |
| harness-built synthetic indexes | Mechanism controls only | Inactive |

SCIP is out of the active comparison because its aggregate index did not establish cross-root identities between separately compiled Maven services. Graphify is out because its product-first pilot was expensive and did not contribute a product-direct finding. Their old runs remain immutable historical evidence.

The comparison holds the provider, model, thinking mode, file-tool surface,
prompt template, estate, and run policy fixed. Alternate Qwen, Haiku, and Luna
agent-only contestants remain available for separate model studies but are
disabled in the product matrix.

## Three tracks

### Track A — agent-only control

The model receives the frozen question, diff, and guarded read-only file tools. This measures what the agent can find without a product.

### Track B — natural adoption diagnostic

The model receives product tools and may choose whether to use them. This track answered an adoption question, not a product-quality question. The pilot showed that tool availability is insufficient: agents often called SCIP or Graphify once and then answered from grep. Track B is therefore frozen as historical diagnostic evidence and is not used to rank products.

### Track C — product-first primary evaluation

Before the model session starts, the harness derives an anchor mechanically from the proposed diff and executes a frozen product-native playbook. The four intentions are constant:

1. Locate the changed concept.
2. Find references or callers.
3. Expand across repository boundaries.
4. Identify tests.

Commands differ because products expose different native operations. Every request, response, failure, version, artifact hash, and duration is persisted as a receipt. The model receives those results as candidates, then uses guarded file reads to confirm, reject, and extend them.

This is deliberate orchestration, not prompt bias. The experiment asks what happens when the product is actually used. The same frozen playbook is repeated for every run of a product, and the agent cannot choose to bypass Phase A.

## Eligibility gates

### Gate 0 — real, fresh, reproducible product

A candidate enters scored smoke tests only when:

- its open-source release, commit, license, and distribution checksum are pinned;
- the actual product indexes all ten pinned repositories;
- its generated artifacts have a reviewable manifest;
- a stale estate, wrong binary, wrong release artifact, or changed index fails closed;
- the adapter exposes only named read operations;
- a manual smoke query returns product output from a known scenario.

RepoWise 0.51.0 and Codebase Memory 0.11.0 passed Gate 0 locally on
2026-09-18 and are enabled for the staged REST-001 scored smoke. This does not
pre-approve their scored runs: Gate 1 is evaluated from each run's receipts.

The Gate 0 measurements were:

| Product | Build | Frozen REST-001 playbook smoke |
|---|---:|---|
| RepoWise 0.51.0 | 1,400 seconds for ten pinned repository clones | 4 steps, 4 receipts, 4 successes; CLI calls 0.7–1.0 seconds and blast radius 15 ms |
| Codebase Memory 0.11.0 | 117 seconds for full plus cross-repository indexing | 5 steps, 5 receipts, 5 successes; calls 4.9–9.8 seconds |

Codebase Memory's ten databases contain 4,613 nodes and 12,054 edges in
aggregate. Its cross-repository pass emitted `CROSS_HTTP_CALLS` edges in this
estate. RepoWise indexed 431 files and 2,375 symbols according to its build
summary.

### Gate 1 — measured use

A natural-adoption run is eligible after at least one successful product call.

A product-first run is eligible only when every scheduled playbook step reached a real product tool, each attempt produced a receipt, and at least one product call succeeded. A missing adapter, unavailable server, or unreceipted step makes the run ineligible. Product failures remain receipts and remain part of product-quality analysis.

`product_direct` is an attribution field, not the utilization gate. An agent may verify product output with source reads and re-attribute the final finding as `file_search`; the receipts still prove that the product participated.

### Gate 2 — evidence-backed contribution

Report separately:

- playbook candidates returned;
- ground-truth items present in product output;
- candidates accepted, rejected, and missed by the verifying agent;
- final findings backed by product receipts;
- additions found only by file search;
- product invocation failures and empty results.

No score may be described as “product X scores Y” unless the run passes Gate 1. The final report must distinguish product retrieval, agent verification, and agent-only recovery.

## Execution order

Do not launch the full corpus immediately.

1. Run one deterministic adapter smoke per product on REST-001. Completed for RepoWise and Codebase Memory on 2026-09-18.
2. Run five repeats of REST-001 for agent-only and each Gate-0 product.
3. Check receipt completeness, output stability, failure rate, token spread, and ground-truth overlap.
4. Continue with a five-scenario balanced slice only if the adapter is reliable and product evidence reaches the model.
5. Run the full corpus only after the slice shows stable operation and a plausible marginal signal.

Overlapping score ranges are reported as inconclusive, not ranked by median. Cost and consistency are first-class outcomes because the pilot showed token use varying by more than 2× between products and by roughly 5× between repeats of the same product.

## Product-specific playbooks

RepoWise uses its released workspace index:

- `search --mode symbol --all` to locate;
- symbol resolution followed by `context --include callers --include callees`;
- `GET /api/workspace/blast-radius` for cross-repository expansion;
- structural path search for test candidates.

The keyless RepoWise index returned promptly for `symbol`, `path`, `context`,
and blast radius. Its fused `auto`, `fulltext`, and `hybrid` modes did not
return within 60 seconds during Gate 0 and are therefore outside this cohort's
allowlisted surface. Adding a hosted embedder would define a different cohort.

Codebase Memory uses its released one-shot CLI:

- `get_graph_schema` to establish the actual graph vocabulary;
- `search_graph` to locate;
- a bounded `query_graph` relationship query for DTO/class references;
- a read-only `query_graph` query over `CROSS_*` edges;
- a read-only `query_graph` query over `TESTS` edges.

`trace_path` remains available for function-shaped queries but is not used for
the REST-001 class anchor. Every product subprocess has a 60-second timeout;
timeouts become failed receipts rather than hanging or disappearing.

Gortex keeps its existing symbol, contract-list, bridge-rank, and dependent traversal playbook.

## Interpretation boundaries

- A missing cross-repo edge is a product result, not an adapter repair opportunity during a scored cohort.
- Product prompts, playbooks, versions, and indexes are frozen within a cohort.
- Setup failures are not model failures; invocation errors are not automatically product defects until the same native command is reproduced outside the agent run.
- RepoWise's AGPL-3.0 license and Codebase Memory's MIT license are recorded as operational facts, not score adjustments.
- Historical synthetic and natural-adoption results stay visible but cannot be merged with product-first results.

Primary product sources: [RepoWise](https://github.com/repowise-dev/repowise), [Codebase Memory](https://github.com/DeusData/codebase-memory-mcp), and [Gortex](https://github.com/zzet/gortex).
