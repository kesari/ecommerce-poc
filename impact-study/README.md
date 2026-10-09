# Cross-Repository Change-Impact Study

This workspace evaluates approaches for discovering and explaining change impact across a Java/Spring microservice estate.

## Current checkpoint

The integrations are prepared for a fresh four-cohort pilot: agent-only,
Gortex-first, RepoWise-first, and Codebase Memory-first. The ten fixture
repositories are pinned and clean; 15 ground-truth scenarios are frozen.
RepoWise and Codebase Memory have setup smoke evidence but no saved scored
model runs. See the [9 October status and restart plan](docs/methodology/2026-10-09-checkpoint-and-restart.md)
for verification, historical score limitations, and the next milestone.

## Documentation

### Architecture

- [Architectural POC proposal](docs/architecture/cross-repo-change-impact-architectural-poc-proposal.md) — research thesis, evaluation model, architecture, and experiment plan.

### Research

- [Current real-product methodology](docs/methodology/product-first-real-product-evaluation.md) — product-first evaluation of Gortex, RepoWise, and Codebase Memory.
- [Historical Gortex vs Graphify vs SCIP/Sourcegraph assessment](docs/research/gortex-vs-graphify-vs-scip-vs-agent-cross-repo-change-impact.md) — preserved background, no longer the active candidate matrix.

### POC fixtures

- [E-commerce microservices POC design](docs/fixtures/ecommerce-microservices-poc-design.md) — canonical design for the synthetic cross-repository system.

## Repository Layout

```text
cross-repo-impact-study/
├── README.md
├── docs/
│   ├── architecture/    # Study architecture and proposals
│   ├── methodology/     # Current executable evaluation protocol
│   ├── research/        # Tool and approach comparisons
│   └── fixtures/        # Designs for synthetic systems under analysis
└── harness/             # Ground truth, pi-based runner, answers, and scoring
```

The e-commerce implementation repositories live in the sibling workspace:

```text
../POC-order-microservices/
```

Keeping fixture documentation here and implementation repositories outside the study repository preserves a clear separation between:

- the experiment definition and ground truth;
- the scoring harness; and
- the independent repositories being analyzed.
