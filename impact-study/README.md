# Cross-Repository Change-Impact Study

This workspace evaluates approaches for discovering and explaining change impact across a Java/Spring microservice estate.

## Current checkpoint

The bottom-up verification milestone is implemented on
`codex/verify-impact-benchmark`: a separate v2 scorer and guarded runner, three
verified records, and executable REST, Kafka and cache boundary checks with
saved evidence. The checks corrected an unsupported failure chain in the
historical REST scenario. The 15 v1 records and saved answers remain historical.

All three native product adapters passed a fresh receipt smoke. Explicit token,
catalog-cost and whole-attempt time ceilings now guard v2 model runs. The
repeated four-cohort model comparison remains pending. The
[revision 2.1 scorer repair](docs/methodology/2026-10-18-scorer-v2.1-repair.md)
now credits independently proved equivalent mappings; historical scores are
preserved, and corrected reruns use a separate run group. Both requested
RepoWise and Codebase Memory reruns now satisfy the bounded repair; their
combined SDK estimated cost is $0.2830784. Codebase Memory's edge identifiers
remain unmatched. The optional trace adapter flag error is fixed and locally
verified in adapter 1.4.1. The
[broader discovery plan](docs/methodology/2026-10-18-broader-discovery-plan.md)
defines twelve new cases and a paired comparison against agent-only.
The earlier smoke and host-sleep interruption remain in the historical
[smoke results](docs/methodology/2026-10-18-v2-smoke-results.md).
See the [implementation status](docs/methodology/implementation-status-v2.md)
for verified outcomes, limitations and remaining gates. The
[9 October checkpoint](docs/methodology/2026-10-09-checkpoint-and-restart.md)
preserves the earlier integration and restart history.

## Documentation

### Architecture

- [Architectural POC proposal](docs/architecture/cross-repo-change-impact-architectural-poc-proposal.md) — research thesis, evaluation model, architecture, and experiment plan.

### Research

- [Current real-product methodology](docs/methodology/product-first-real-product-evaluation.md) — product-first evaluation of Gortex, RepoWise, and Codebase Memory.
- [Bottom-up repair plan](docs/methodology/bottom-up-verification-repair-plan.md) and [v2 acceptance contract](docs/methodology/acceptance-contract-v2.md) — independent verification before model comparisons.
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
