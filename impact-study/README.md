# Cross-Repository Change-Impact Study

This workspace evaluates approaches for discovering and explaining change impact across a Java/Spring microservice estate.

## Documentation

### Architecture

- [Architectural POC proposal](docs/architecture/cross-repo-change-impact-architectural-poc-proposal.md) — research thesis, evaluation model, architecture, and experiment plan.

### Research

- [Gortex vs Graphify vs SCIP/Sourcegraph vs agents](docs/research/gortex-vs-graphify-vs-scip-vs-agent-cross-repo-change-impact.md) — comparative assessment of the candidate approaches.

### POC fixtures

- [E-commerce microservices POC design](docs/fixtures/ecommerce-microservices-poc-design.md) — canonical design for the synthetic cross-repository system.

## Repository Layout

```text
cross-repo-impact-study/
├── README.md
├── docs/
│   ├── architecture/    # Study architecture and proposals
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

