# Infrastructure discovery proofs

These protected cases are outside the indexed estate. `verify.py` extracts pinned
source archives, applies complete new upstream patches, compiles each full origin
module offline, and executes the unchanged protected Java assertion against every
phase. It never invokes a model. Criteria and test digests must come from an
independent reviewer; the verifier does not approve its own new digest.

- H05 changes Payment's charged-result publication topic to `payment.charged.v2`.
  Actual `PaymentCommandService` produces the result and actual Payment
  `OutboxPublisher` sends it through a real Kafka broker. The main registered
  Order listener and `SagaOrchestrator` must reach `INVENTORY_COMMIT_PENDING`,
  record a payment ID and emit `inventory.commit.requested`. The main observation
  window is 1,800 ms after an independent broker read. The old-topic positive
  control proves the registered consumer is alive when the new topic disconnects
  it. A missing main subscription repair fails the same business assertion.
- H06 changes Order's reservation-item payload key from `quantity` to `units`.
  Actual `OrderService.place` and Order `OutboxPublisher` feed a real broker and
  the registered Inventory listener/application. The literal result is one
  reservation for quantity 3 and stock reduced from 10 to 7. The main observation
  window is 2,200 ms after an independent broker read. A fresh old-shape control
  proves listener liveness after a rejected new-shape message. Removing the read
  mapping repair fails the same quantity assertion. Retry handlers may execute
  incidentally; retry timing, headers and DLQ behavior are not scored claims.
- H09 is a compatible private SQL change: a new forward V3 migration renames
  `price_minor` to `unit_price_minor`, and matching SQL retains the `priceMinor`
  Java alias. Existing V1/V2 migrations are untouched. A real PostgreSQL database
  is first migrated to V2 with existing data, then upgraded. Detail, list, batch,
  update/read-back and the actual Basket HTTP client preserve prices. A valid SQL
  projection that adds 1 to the read value must fail the same public-price check.
- H10 is a compatible private cache change: ProductCache stores a versioned
  envelope while decoding new entries and legacy raw products. Real Valkey cold,
  warm and legacy reads preserve the published price, including the actual Basket
  HTTP client. A valid envelope containing a price increased by 1 must fail the
  same warm-price assertion.

H09/H10 need baseline, compatible changed and incompatible faulty-change phases;
the changed implementation is already complete and needs no extra repair phase.
Neither private-storage case implies a downstream production edit. Only the
explicitly executed consumer surfaces are established.

SQL/cache HTTP checks use the actual Catalog controller via MockMvc and a
loopback HTTP bridge consumed by the actual Basket client. They do not boot the
full Spring Security deployment. SQL isolates the cache, and cache tests use a
controlled repository seed. Messaging uses actual production logic with
controlled persistence ports; database transactions and broader saga completion
are outside its proof.

Only cached images are used. Ports bind to loopback with random host ports.
Containers carry a unique verifier label and close through Java `try`/`finally`,
with a Python owned-label cleanup fallback. Ryuk is disabled to avoid a Docker
socket helper mount. No privileged containers, daemon configuration changes or
administrator actions are needed. Every attempted run, including infrastructure
failures, is initially captured in fresh `/private/tmp/discovery-infra-*`
directories outside the study and source estate. Publish only the portable
evidence directory into `infra/evidence/` after execution completes. The verifier
rejects paths inside the study/estate, existing outputs, and overlapping runtime
and evidence paths, including resolved symlinks. Do not copy `target/` output here.

Example (substitute independently reviewed input digests):

```sh
python3 harness/verification/discovery/infra/verify.py \
  --case H05 --estate /path/to/estate \
  --runtime /private/tmp/discovery-infra-h05-new \
  --evidence /private/tmp/discovery-infra-h05-new-evidence \
  --trusted-inputs '{"criteria.json":"...","MessagingBoundaryTest.java":"...","StorageBoundaryTest.java":"..."}'
```

After the command finishes, copy that portable evidence directory to a new
`infra/evidence/H05-new` directory; preserve failed attempts as well. Never point
live JVM observation/report paths into the study or indexed estate.

Current evidence (2026-10-19): all four runtime proofs passed. H05/H06 each
passed baseline and sufficient repair, and unchanged business assertions rejected
the changed implementation and missing-repair fault. H09/H10 each passed baseline
and complete compatible change, while the same assertions rejected the
incompatible price fault. `verified-manifests.json` identifies the completed
portable bundles and their digests for independent review; it is an index, not
self-approval of trusted oracle claims.

All 28 earlier offline compilation checks remain under
`evidence/compile-only-20261019`. Historical H05 attempt 1 retains external Docker
socket absence, and attempt 2 retains the Kafka port fixture mistake. The root
reviewed the correction from 9093 to the pinned container's exposed 9092 port
before the successful third attempt. Business assertions and criteria did not
change. All live runtime output was captured outside the study and estate, then
portable bundles were copied here after completion. No installer, daemon restart,
administrator action or paid model call was part of these checks.
