# Independent verification

The prediction agent remains read-only. These tests, criteria, patches and logs
are evaluator inputs, outside the ten allowlisted service repositories. Never
index this directory or add it to the model's searchable estate. Runtime output
must be written outside both the service estate and study repository.

## REST-001 first slice

This v2 scenario renames only Account's **response** field to `postcode`. Account
requests remain unchanged. The producer's entire pinned source is compiled;
its actual `AddressResponse` is serialized and served over loopback HTTP. The
actual Order `AccountClient`, `CheckoutService`, and `ShipmentClient` execute.
Basket data, quote persistence and the successful Shipment HTTP response are
controlled fixtures. No mock replaces Account HTTP serialization/deserialization
or the Order code under investigation.

The reviewed external oracle digests are:

- Criteria: `597434b2d44770fe5e5e577f5287d7564ea527eefd234eee2c43a780655782dc`
- Java test: `0ec0abbcb2c5e9e4ce002f048421bc2da77522a799e018885b99176896527a54`

From the study root, with Maven dependencies already cached:

```sh
python3 harness/verification/verify_rest001.py \
  --estate /Users/rajmohan/Projects/POC/POC-order-microservices \
  --output /private/tmp/rest001-fresh-attempt \
  --java-home /Users/rajmohan/.sdkman/candidates/java/21.0.10-tem \
  --trusted-criteria-sha256 597434b2d44770fe5e5e577f5287d7564ea527eefd234eee2c43a780655782dc \
  --trusted-test-sha256 0ec0abbcb2c5e9e4ce002f048421bc2da77522a799e018885b99176896527a54
```

The output directory must be new. Maven runs offline; loopback binding must be
permitted. A blocked socket or missing dependency is a failed attempt, never a
scenario pass. Preserve failed manifests. Do not recompute a new trusted digest
as a substitute for reviewing an intentional oracle change.

The snapshot digest hashes compact, sorted JSON `{repo:{commit,tree}}` for all ten
pinned repositories. It is distinct from the agent runner's working-copy content
fingerprint. Full Account/Order archive file hashes and exact phase patches are
also saved. Pinned working copies are never edited.

All four checks have passed; portable evidence is in `evidence/REST-001/`:

| Phase | Observed result |
|---|---|
| Baseline | Postal code binds, Shipment HTTP reached, quote saved |
| Renamed response | Postal code silently becomes null; Order `ShipmentClient` throws at `Map.of` **before Shipment HTTP**; no quote saved |
| Consumer read alias | New producer field retained, postal code binds, original Shipment request and quote restored |
| Wrong alias fault | The unchanged repair-success assertions reject the fault at `REPAIR_BINDING` |

The historical null-to-Kafka/DLQ story is **not established** by this normal
checkout path: execution stops earlier. This slice does not establish provider
Spring routing/security, actual database persistence, UI behavior or Kafka
delivery. It validates a bounded boundary and a repair, not the entire estate.

## INT-002 benign cache namespace control

`verify_int002.py` uses the actual `ValkeyEstimateCache` and
`ShipmentService.estimate` against a disposable real Valkey container. It requires
the already cached `valkey/valkey:8` image and never pulls. Container ports bind
only to loopback; its exact image ID, command logs and cleanup result are saved.

The initial independently specified oracle hashes (review before campaign use)
are criteria `a9641ec955956d8573540f0b7e257ff9a1e84a12b8beb32f2aa222bfe3b3ec38`
and Java test `5c689c5c0fbb0b847179e2addff1b216b5e861af119bc0c4cb2b2c536dd2282a`.
Invoke the same flags as `verify_rest001.py`, substituting these hashes and a
fresh output directory. Docker access is additionally required.

Portable execution evidence is in `evidence/INT-002/`. Baseline reads a seeded
old key. The changed prefix misses that entry, recomputes the literal expected
charge/dates, preserves the old entry, writes the new key and serves the next
request as a hit. The repaired phase intentionally makes no further edit because
the change is benign. A targeted return-null fault after a miss fails the same
`ESTIMATE_COMPATIBILITY` assertion. This proves the bounded cache behavior; no
HTTP, database persistence or whole-estate behavior is claimed.

## KAFKA-001 topic routing boundary

`verify_kafka001.py` compiles pinned Order `EventEnvelope`/`OutboxPublisher`, actual
Basket/Shipment listeners and their application services together using the
existing Shipment Maven dependencies. Spring registers the real `@KafkaListener`
annotations. Each phase uses a fresh real Kafka broker from the cached
`apache/kafka:4.0.0` image. Unrelated persistence ports are controlled in memory;
no database or transaction claim is made.

The final specified oracle hashes (review before campaign use) are criteria
`09f8d989c7f92bd9f37397cea66501890e67a691ba0334801563efc4ee589a36` and Java test
`a78b3e84311432e79973717f4e218ec9ce993898663e3e4cab23510da3193649`.
Use the same verifier flags, plus
`--docker-host unix:///Users/rajmohan/.docker/run/docker.sock` when Docker Desktop
does not expose its socket at the platform default. Maven stays offline.

Portable evidence is in `evidence/KAFKA-001/`. The producer-only rename sends to
`v2` (confirmed independently by a broker read) while both `v1` consumers remain
idle for the specified two-second observation window. A positive control on the
old topic proves the listeners are live. Migrating both main subscriptions to
`v2` restores handler calls to Shipment's save port and Basket's checkout port.
The fault leaves Basket on `v1`: Shipment receives the event, Basket does not,
and the unchanged `BASKET_CONSUMER_DELIVERY` assertion rejects the repair. An old
topic positive control also proves the faulted Basket listener remains live.

Retry/DLQ migration, durable database effects and a complete distributed journey
remain unverified. The v1 proposed literal `kafkaTemplate.send` snippet does not
describe the actual producer implementation; the complete executable patch
changes the `EventEnvelope.topic()` branch only for `order.confirmed`.

## Verified v2 record and oracle bindings

The three bounded records now live in `harness/records/v2/`. Their expectations
are intentionally narrower than the historical v1 narratives. `INT-002` has no
production repair action and a validation-only obligation. `KAFKA-001` requires
both main-topic subscriptions to migrate; it makes no assertion that retries,
DLQs or database effects have been verified. No other repository is silently
classified unaffected by these scoped tests.

Each `evidence/<change>/oracle-bundle.json` attests exact predicates, hashes all
referenced execution artifacts, and binds the canonical full record including
its complete proposed diff. The saved bundle pins are:

| Record | Oracle bundle SHA256 |
|---|---|
| REST-001 | `33b42c1fca9396918b35c584076cca9cb5351ceb09d59889d5c195fd050d12d6` |
| INT-002 | `6e9da31219f08cf19efd59208489fe6bfadecb79988480ca27cd6b8bf9d52c37` |
| KAFKA-001 | `306d030809024cdba8d209e000dfff2258e51c31b20243f9eabb20e8d68751ab` |

The external caller supplies the reviewed literal pin to scorer/runner
preflight. Computing a hash from whatever bundle happens to exist does not
establish trust. `build_oracle.py` reproduces the cache/Kafka bundles from the
already executed evidence, without containers or model calls. Its fixed record,
test, criteria and execution-manifest bindings require review when changed.
For example, generate a fresh output rather than replacing a reviewed bundle:

```sh
python3 harness/verification/build_oracle.py \
  --record harness/records/v2/INT-002.json \
  --evidence-root harness/verification/evidence/INT-002 \
  --trusted-manifest-sha256 79581351534b03b5132fff825babb6bf2099194c7cf01e826e5c27520b076fe9 \
  --output /private/tmp/int002-reviewed-oracle.json
```

`python3 -m unittest discover -s harness/verification -p 'test_*.py'` checks
output isolation, immutable evidence, the literal external bundle pins, altered
claim/patch/observation rejection, and pilot scoring regressions. This is a
deterministic verification gate; these unit fixtures are not model observations
or product comparison results.
