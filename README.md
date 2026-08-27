# Cross-Repository Change-Impact POC

This POC asks one practical question:

> **If I change something in one repository, what else will break?**

The e-commerce services are test data. They give us realistic REST calls, Kafka
events, database schemas, frontend consumers, and transitive failures to find.

[Simple overview](https://kesari.github.io/ecommerce-poc/) ·
[How the comparison works](https://kesari.github.io/ecommerce-poc/architecture.html) ·
[Run it](https://kesari.github.io/ecommerce-poc/running-locally.html) ·
[Test data and scores](https://kesari.github.io/ecommerce-poc/contracts.html)

## The four approaches in plain English

| Approach | Simple explanation | Best at | Main limitation |
|---|---|---|---|
| **SCIP / Sourcegraph** | A compiler-accurate map of the code | Exact definitions, references, and shared-library usage | REST, Kafka, and serialized data are not always code references |
| **Gortex** | A map of how services communicate | Cross-repo REST, event, schema, and blast-radius questions | More infrastructure and maturity risk |
| **Graphify** | A broad, lightweight graph for an agent to explore | Quick setup, visualization, code plus documentation context | Less authoritative for exhaustive impact analysis |
| **Claude Code / Codex** | An investigator that searches and reasons while doing the task | Meaning, business rules, fresh changes, and explanations | Cannot prove it searched everything |

The POC is not trying to pick one tool for every job.

The likely useful combination is:

```text
SCIP                 Gortex               Claude / Codex
exact code truth  +  service topology  +  reasoning and explanation
```

Graphify is the lightweight control: it tells us how much value a simpler broad
graph adds before we pay for a specialized system graph.

## Gortex / Graphify comparison

| Question | Gortex | Graphify |
|---|---|---|
| What kind of graph? | Purpose-built code and system dependency graph | Broad code, docs, config, and concept graph |
| REST and Kafka links | First-class provider/consumer relationships | Usually extracted or inferred context |
| Blast-radius analysis | Core use case | Agent traverses the graph and reasons |
| Evidence | Strong structural and contract provenance | Clear extracted-versus-inferred provenance |
| Adoption | More setup and operational cost | Simpler and more portable |
| Best role in this POC | Main system-topology candidate | Lightweight graph control |

The deciding question is simple:

> **Does Gortex find important cross-service dependencies that SCIP plus a good
> coding agent misses often enough to justify another graph platform?**

## One example

Suppose Account renames an address JSON field:

```text
postalCode  →  postcode
```

A text search finds Account and the web app. An exact code index finds typed Java
references. But the real failure is transitive:

```text
Account JSON
    ↓
Order silently binds postalCode as null
    ↓
Order publishes an unchanged Kafka event containing the null value
    ↓
Shipment rejects the value and sends the event to its DLQ
```

Shipment never references Account's field directly. This is the kind of
cross-repository impact the POC is designed to measure.

## How the experiment works

1. Write down the correct blast radius before running any tool.
2. Give every contestant the same change question and repository estate.
3. Compare what each contestant found against the frozen answer.
4. Penalize missed critical dependencies more heavily than extra suggestions.
5. Record the evidence behind every finding.
6. Measure what the graph found that the simpler baseline did not.

The current worked scenario is `REST-001`. The planned study expands to 30
changes covering Java symbols, shared libraries, REST, Kafka, schemas,
configuration, and business rules.

## Run the scorer

```bash
cd impact-study/harness
python3 -m unittest discover -s scoring -p 'test*.py'

python3 scoring/score.py score \
  --ground-truth records/REST-001.json \
  --answer answers/examples/REST-001-agent-only.json

python3 scoring/score.py marginal \
  --ground-truth records/REST-001.json \
  --baseline answers/examples/REST-001-agent-only.json \
  --candidate answers/examples/REST-001-gortex.json
```

## Repository layout

```text
impact-study/       research, frozen answers, runner, and scorer
*-service/          independent Java fixture repositories
commerce-bff/       browser-facing backend fixture
commerce-web/       React consumer fixture
commerce-platform/  Kafka, PostgreSQL, Valkey, observability, and E2E tests
docs/               the GitHub Pages site
```

The harness currently runs Claude and Codex as agent-only baselines. The answer
format supports SCIP, Gortex, Graphify, hybrid retrieval, and the combined
approach; those integrations and the remaining corpus are the next POC work.

For the full rationale and detailed scoring model, read the
[architectural proposal](impact-study/docs/architecture/cross-repo-change-impact-architectural-poc-proposal.md)
and the
[original comparison](impact-study/docs/research/gortex-vs-graphify-vs-scip-vs-agent-cross-repo-change-impact.md).
