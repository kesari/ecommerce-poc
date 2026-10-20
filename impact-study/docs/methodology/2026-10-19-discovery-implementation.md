# Discovery implementation and verification

Started 2026-10-19; updated 2026-10-20. Twelve runtime cases are verified. No paid
model development runs or final comparisons have been executed in this phase.

| Case | Supplied change | Independently executed result |
|---|---|---|
| H01 | Catalog currency key becomes `currencyCode` | Basket binding and web currency normalization both require repairs; BFF forwarding remains compatible |
| H02 | Shipment fee key becomes `feeMinor` | Actual Order client/checkout loses shipping charge; alias restores it; wrong alias rejected |
| H03 | Basket total key becomes `payableMinor` | BFF forwards exact response; web requires normalization; actual Order snapshot remains compatible |
| H04 | Catalog image key becomes `thumbnailUrl` | Web image reads require repair; Basket's used fields remain compatible |
| H05 | Payment result topic becomes `payment.charged.v2` | Real Kafka delivers the publication; Order remains payment-pending until its listener subscribes to the new topic |
| H06 | Inventory-reserve item key becomes `units` | Real Kafka delivers the message; actual Inventory listener reserves zero until its item mapping is repaired |
| H07 | Same-name Shipment fee reports whole INR | Actual Order quote requires multiplying by 100; factor-10 fault rejected |
| H08 | Nonmetro computed SLA changes from 4/6 to 5/7 days | Order consumes new dates without an edit; Shipment cache closure establishes the explicitly allowed prior-entry lifetime |
| H09 | Private SQL column rename plus mapper/migration update | Real PostgreSQL/Flyway/MyBatis preserve public prices and updates; corrupt projection rejected |
| H10 | Private cache envelope with legacy fallback | Real Valkey cold, warm and legacy reads preserve public prices; corrupt warm envelope rejected |
| H11 | Optional readonly merchandising field | Service/cache roundtrip, Basket, BFF and existing web consumption remain compatible |
| H12 | Equivalent ordered list-mapping loop | Service/cache values and web consumption remain compatible; price-corruption fault rejected |

[All twelve records](../../harness/records/v2/) and their
[frozen case manifest and oracles](../../harness/verification/discovery/frozen/cases.json)
are bound to independently selected execution-manifest hashes and full-record
review hashes. The builder validates protected assertions, source pins, command
outcomes, observations and granular claim artifacts. Promotion requires a
separate reviewed-record pin registry. Failed setup attempts and earlier drafts
remain separate; compilation alone was never counted as runtime verification.

The public task contains the origin patch and all ten repository IDs, without
naming expected consumers, repair counts or dependency chains. Every case uses
the same public evaluation policy: Java HTTP clients, normal first message
delivery on provisioned topics, proxy forwarding, web data consumption and
private SQL/cache boundaries. Supplied migrations are assumed applied. Rollout,
provisioning, security, retries and DLQ behavior are explicitly outside the
requested repair; recommendations for them remain separate. This avoids scoring
a legitimate provisioning recommendation as a missing business repair.

H07 explicitly covers nonnegative whole-INR amounts whose original minor-unit
values are divisible by 100. H08 explicitly permits prior cached estimates until
the existing TTL expires. Its cache-branch test uses a controlled cache and
manual expiry; it does not measure Valkey's timer. These are public policies,
not hidden exceptions.

## Scoring and paired measurement

[Revision 2.2](v2-scoring-contract.md) adds `scale_numeric_field`, validates
repository identities against the public inventory and reports
`complete_supported_impact`. This primary outcome requires supported runtime
observations, causal edges, sufficient repair actions and validation obligations
from one coherent plan. Missing critical claims, established contradictions,
malformed claims and unverified material repair/failure claims prevent success.
Supporting symbols remain diagnostic. Required-action coverage and the earlier
bounded-repair outcome remain separate. Older records have no discovery primary
outcome; their scores and the [2.1 replay snapshot](../../harness/history/2026-10-19-v2.1/README.md)
are preserved.

The [paired analyzer](../../harness/run/paired-campaign.md) uses twelve cases,
four arms and three repetitions: 144 scheduled attempts. It binds every outcome
to a trusted case/repetition/arm identity and independently regrades scores and
receipts. Missing observations stay unknown; actual failures remain in the
operational denominator. It averages repetitions within each case, weights cases
equally, and reports case-cluster intervals plus six-family sensitivity. A
practical-benefit decision requires a complete balanced campaign, at least a
10-point gain, a positive interval lower bound and compatible-control safety.
Intervals remain exploratory across three product comparisons.

Execution requires frozen records, oracles, task prompts, model/SDK, harness,
arm configuration, indexes and ceilings. Protected identities are stamped before
model JSON is parsed. Repeated slots are rejected. A shared durable campaign
ledger reserves each attempt's full cost ceiling before its worker starts;
concurrent processes share the approved total, unknown exposure stays reserved,
and crashes cannot erase the reservation. Provider execution is disabled in the
prepared campaign: no new spend allowance is approved by this work.

## Native operations and limits

All 23 operations exposed by the three active product adapters were invoked on
development identifiers with zero provider calls. RepoWise and Codebase Memory
successful, empty and ambiguous outcomes are retained. Codebase Memory's exact
qualified trace succeeds. Gortex graph and bridge operations succeed on resolved
IDs, but its native API-impact view does not resolve the tested Java address
route. This is a recorded extraction limitation, not evidence that the HTTP
contract is absent. Adapter 1.4.2 corrects the API-impact argument to a native
path substring and retains requested/executed queries. A native regression
checks that translation even when route extraction returns empty.

See [native operation evidence](../../harness/indexes/smokes/2026-10-20-operations-resolved/README.md),
[HTTP evidence](../../harness/verification/discovery/http/RESULTS.md),
[web/BFF evidence](../../harness/verification/discovery/web/README.md) and
[infrastructure evidence](../../harness/verification/discovery/infra/README.md).
Real dependency checks use cached images and controlled persistence where
specified; they do not establish full deployed journeys, security or retry/DLQ
correctness. The finite protected repairs do not cover every possible valid
implementation; unreviewed alternatives require independent attestation before
receiving equivalent repair credit.

Docker became available after the user started it. Kafka, PostgreSQL and Valkey
checks completed and verifier-owned containers were cleaned up. No `sudo`,
administrator access, privileged containers or Docker updater approval was used.
The user-managed Docker Desktop engine was not restarted by this continuation.

## Remaining execution gates

1. Review native extraction limitations and run the planned eight development
   model attempts (one breaking and one compatible development case per arm)
   only when separately authorized and funded. They have not run here.
2. If development requires a prompt, adapter or scorer change, refreeze before
   any of the twelve comparison cases are predicted. Keep old artifacts.
3. Approve a distinct total campaign allowance and execute the 144 attempts on
   an awake host. Preserve failures, missing outcomes and unresolved exposure.
   No product benefit or winner has yet been measured.

## Offline validation and prepared artifacts

The final checks cover 258 Python tests and 101 Node tests, with no skips.
The Python suites include all twelve full-proof preflights, missing/wrong-action
and compatible-control mutations, 36 paired-analysis tests and four historical
replay checks. Node checks include real native tool regressions, parallel-process
budget contention, abrupt-exit reservations/slot markers and zero-provider
preflight rejection. All ten fixture repositories remain clean at their pins;
tracked source passes `git diff --check`.

The [prepared campaign](../../harness/verification/discovery/campaign-2026-10-20-final/README.md)
contains frozen conditions, the seeded 144-slot schedule, externally recorded
input pins and an empty-ledger analysis. It reports 144 unrun slots rather than
144 incorrect model answers. An actual CLI invocation was rejected with
`paid runs are not authorized; no provider may initialize`. Its total approved
allowance is zero. Earlier preparation artifacts were superseded before any
model attempt and remain available separately.
