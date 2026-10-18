# REST-001 independently verified repair equivalences

This is a new proof bundle. The historical REST record, tests, criteria, bundle
and execution artifacts remain unchanged. `reference/` contains byte-identical
copies of the historical artifacts. `method-mapping/` contains a fresh isolated
execution against the same pinned Account and Order revisions.

The alternative repair changes only `AccountClient.address`: it reads the real
HTTP response as a JSON tree and maps `postcode` into the original postal-code
snapshot value. The nested `AddressResponse` record is unannotated and its full
component declaration is identical in all four phases. Thus the proof does not
quietly add the reference record annotation while claiming a method-only fix.

All four original acceptance phases ran with the original protected test and
criteria bytes. Correct mapping restores postal code `560001`, reaches Shipment
HTTP once and saves a quote. A deliberately wrong mapping to `wrongPostcode`
produces null and fails the same original `REPAIR_BINDING` assertion. The
upstream response keeps `postcode` only. Maven compiled the full Account producer
and Order client project offline. No model provider was called.

Exact source checks and `javap` output establish that the record component
`AddressResponse.postalCode` and generated `postalCode()` accessor refer to the
same String member. The unique method signature is `address(String, UUID)`;
`address(UUID)` is not certified. The new oracle enumerates only exact aliases:

- Record accessor spellings with `#postalCode()` or `.postalCode()`.
- The tested `.address` repair target and explicit String/UUID signature forms.
- The renamed `postcode` field for the same directed Account-to-Order GET route.

Repository, declaring record, direction, route, operation, before/after values
and all other semantic fields remain significant. No class-prefix matching or
cross-repository alias is introduced. Duplicate aliases collapse to one claim.

Pins selected for review:

- Original criteria: `597434b2d44770fe5e5e577f5287d7564ea527eefd234eee2c43a780655782dc`
- Original test: `0ec0abbcb2c5e9e4ce002f048421bc2da77522a799e018885b99176896527a54`
- New method execution manifest: `8459ba53740421fe04b56078e0ad7fcc5c919c10fea62964fc2ae5ab440f72b3`
- New `oracle-bundle-v2.1.json`: `d6ed60fa90d5183dc6b6582effdbfef99d4cb88f9436ae64dbccc0c015d3b317`

The bundle uses the reviewed `scoped_reference_v1` scoring policy and is bound
to the unchanged complete REST-001 record and estate snapshot. A verifier must
receive its independently reviewed literal bundle pin. The old bundle remains
usable for historical scoring; do not overwrite it or pool different evaluation
revisions without labeling them.

`test_rest001_equivalence.py` checks the immutable original bytes, runtime proof
bindings, source/member identity, wrong-repository/record/method rejection,
directed field aliases and duplicate handling. Database persistence, provider
Spring routing/security, real Shipment-server behavior, Kafka and full E2E are
outside this HTTP-boundary proof.
