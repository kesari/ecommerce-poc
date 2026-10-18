# V2 acceptance contract

Status: implementation contract, 2026-10-16. The user approved the bottom-up
repair plan before implementation. This matrix makes the first implementation
slice explicit; runtime findings may correct v1 notes but never overwrite them.

## Scope and rules

The agent predicts impact and remediation; it does not edit repositories. The
benchmark verifier independently executes protected checks. Only public question,
proposed diff, original estate, and selected product results reach the agent.
Expected answers, repairs, verification tests, and verification logs stay outside
both the searchable estate and product indexes.

Correctness has three independent dimensions: broken downstream behavior before
repair, production edits under an accepted repair strategy, and validation-only
obligations. Finding an affected service does not imply its implementation must
change. Equivalent repairs are accepted against the same behavioral contract.
Cost and time never compensate for a critical miss. Historical v1 scores remain
historical and cannot be combined with v2.

## First vertical slice: REST-001 response-only rename

The v2 change renames only the Account address **response** JSON component from
`postalCode` to `postcode`. The request API still accepts `postalCode`. This is
an explicit refinement of the ambiguous v1 diff, not an amendment to v1.

| ID | Starting state / action | Required observable result | Independent check / evidence |
|---|---|---|---|
| R1 | Archive pinned Account and Order revisions; compile baseline | Actual DTO, AccountClient, ShipmentClient and CheckoutService compile | Maven exit code and source snapshot hash |
| R2 | Serve baseline Account response through real HTTP and invoke AccountClient | Address snapshot has the expected postal code | Literal test assertion through actual client |
| R3 | Apply response-only rename and repeat identical request | Provider emits `postcode` and no `postalCode`; request contract remains unchanged | Actual DTO serialization assertions and complete patch hash |
| R4 | Send changed response through unchanged AccountClient | Observe actual deserialization result or failure, rather than adopting v1's explanation | Assertion and raw failure/observation log |
| R5 | Execute actual checkout/ShipmentClient with the changed binding | Establish first failure, whether Shipment HTTP was reached, and whether a quote was saved | HTTP request counters, quote repository observation, thrown exception |
| R6 | Keep producer rename; add compatible read binding in Order | Original value and successful estimate/quote behavior restored | Same unchanged acceptance checks on repaired copy |
| R7 | Replace repair binding with an incorrect alias | Protected repair acceptance check fails; verifier records the expected rejection | Nonzero test result, failure class/assertion and artifact hash |
| R8 | Name correct symbol in wrong repo; claim wrong consumer edge | Exact symbol/edge correctness decreases | Independent scorer regression literals |
| R9 | Provide nonempty but unrelated explanation | No semantic evidence credit without trusted snapshot-bound attestation | Missing/broken attestation tests |
| R10 | Change token count, latency or claim ordering | Correctness remains equal; duplicate claims add no credit | Metamorphic scorer tests |

The in-memory port used only to observe quote persistence does not establish
PostgreSQL behavior. A loopback endpoint does not establish a fully running
Account or Shipment application. There is no proven Kafka propagation until a
real-broker journey establishes it. Unsupported full-system claims are unresolved.

The minimal reference repair is an Order consumer read alias. Other strategies
must retain the response-only rename, preserve the request contract and agreed
behavior, and be independently verified before they become accepted alternatives.
No automatic acceptance of any superficially similar implementation.

## Later scenario gates

| Scenario | Expected question | Real dependencies required before runtime qualification |
|---|---|---|
| INT-002 | Does a private cache namespace change preserve the user-visible estimate and require no downstream edits? | Real cache integration plus same estimate acceptance check |
| KAFKA-001 | Does a producer topic rename orphan the actual consumer subscriptions, and does an agreed migration restore them? | Real Kafka producer, Basket/Shipment consumers, and any persistence used by the checked path |

Real dependencies that are unavailable yield an infrastructure-blocked result,
not a product failure or a passing mocked substitute. The 60-run model pilot
cannot begin until all three scenario gates and the protected-verifier gates
pass, and a campaign cost ceiling is recorded.

## Evidence and trust

Protected acceptance tests define expected values before execution. Every result
binds to criteria, tests, patches, commands, exit codes and source revisions.
Oracle manifests require an independently selected trusted digest; reading a
manifest and trusting its own digest is insufficient. A filename/line existing
or a receipt ID resolving establishes a locator, not semantic truth.

Do not expose expected claim IDs to agents. Match semantic structured claims
against the independently established predicate set. Preserve actual failures,
setup failures and incomplete checks, and never regenerate expected outcomes
from scorer output merely to make tests green.
