# Ground-Truth Corpus

Frozen before any contestant runs (architecture proposal section 6).

## Source

Derived from `docs/fixtures/ecommerce-microservices-poc-design.md` section 21:
ten canonical change scenarios across the e-commerce estate. Each canonical
scenario expands into two or three symbol-level variants to reach roughly 30
records, plus DB-migration and internal-refactor records that fit the estate.

## Composition deviation from the architecture proposal

The proposal suggests 10 REST / 5 Kafka / 5 shared-library / 5 DB / 5 internal
changes. The fixture estate intentionally has **no universal shared library**
(fixture design section 3 non-goals), so the shared-library category is
replaced by additional REST contract and Kafka schema variants:

```text
~12 REST/OpenAPI contract changes   (incl. BFF-facing and service-to-service)
~7  Kafka/AsyncAPI schema changes   (saga topics, envelope, enums)
~4  DB/migration changes            (Flyway column/type changes with read models)
~7  internal refactors              (single-repo and cross-repo code-level)
```

## ID scheme

```text
REST-###    REST/OpenAPI contract changes
KAFKA-###   Kafka topic/schema changes
DB-###      migration/schema changes
INT-###     internal refactors
```

## Rules

1. A record is frozen once committed here; edits require a new commit note
   explaining why the ground truth changed.
2. Every affected repository carries a criticality so misses can be triaged by
   severity.
3. Seeded change branches in the estate repositories reference the record id,
   for example `gt/rest-001`.
