# Version 2 structured scoring contract

Implemented 2026-10-16; scoped revision **2.1** added 2026-10-18. This is a separate diagnostic scorer; v1 schemas,
records, scorer and historical scores retain their original meaning. It does
not by itself establish that a whole-estate scenario has been verified.

## Acceptance predicates

Both record and answer use integer `protocol_version: 2`, `change_id`, and
`snapshot_sha256`. Records additionally name `origin_repo`, `proposed_change`,
`category`, `query`, and `status` (`draft` or `verified`). The proposed change
is assumed applied; actions describe remediation beyond that supplied change.

Answer `findings` contains five arrays. These are the exact semantic keys:

| Kind | Matching keys |
|---|---|
| `repositories` | `repo`, `runtime_status` |
| `obligations` | `repo`, `obligation` |
| `symbols` | `repo`, `fqn` |
| `edges` | `provider`, `consumer`, `type`, `identifier`, `field` |
| `actions` | `repo`, `target`, `operation`, `before`, `after` |

Repository runtime statuses describe behavior after the proposed change and
before repair: `broken`, `unchanged`, `unresolved`. They do not establish a
production edit. Repository obligations are `production_edit`, `validation_only`,
`unaffected`, or `unresolved` **under a selected repair alternative**. Each
record alternative contains a complete `actions` array and an `obligations`
entry for every repository in scope. An action's optional `critical: true` is
private record metadata; an answer never needs to know an expected item ID.

Under the original, no-policy strict mode, an accepted repair requires the exact
complete action and obligation sets from one alternative. Mixing actions from
alternative plans is not accepted. Partial
precision/recall and critical downstream misses are also reported for every
alternative. The report's selected alternative is deterministic: complete
matches first, then action F1, then obligation hits, then lexical plan name.
Trusted scoped mode additionally prioritizes required-action coverage after
complete matches; its bounded acceptance meaning is defined below.
F1 is used only to choose the diagnostic comparison plan; it is not a blended
quality/cost result. All alternative details remain available for review.

The pilot's public action vocabulary is deliberately small:

| Operation | Predicate |
|---|---|
| `read_json_field` | A scoped binding reads the new JSON key rather than the old key |
| `retain_json_alias` | A producer contract preserves an explicitly permitted compatibility key |
| `subscribe_topic` | A scoped consumer listens to the new topic rather than the old topic |
| `publish_topic` | A scoped producer publishes to the new topic rather than the old topic |

`target` is an exact declaring member or contract location. `before` and
`after` are exact old/new contract values. An accessor rename and an equivalent
alias can satisfy the same `read_json_field` behavior; the scorer does not require
one reference patch's syntax. The acceptance author must establish that the
behavioral predicate is sufficient. This bounded vocabulary does not claim to
understand arbitrary natural-language edits or unlisted valid repair strategies.

Values retain case and member identity. Leading/trailing whitespace is ignored
for identity matching; controlled enum values remain schema-strict. A class
prefix cannot establish a member-level claim. Provider -> consumer is data-flow
direction, even when the consumer initiates the HTTP call. Edge types are `rest`,
`kafka`, `grpc`, `db`, `local`; use an empty `field` for a field-independent edge.

Claims may carry `evidence`, `citations` (`path`, optional `sha256` and `line`),
and `receipt_id`. These do not affect predicate matching. Unexpected semantic
fields or malformed claims are reported explicitly and count against precision.
Duplicate and reordered claims add no credit. Top-level runner metadata is
permitted but never used as semantic proof. `synthetic: true` cannot be scored
as a real agent observation.

## Independent evidence boundary

Structured correctness is diagnostic until the independent verifier supplies
proof. No nonempty explanation, existing source line, product receipt, or
model-authored manifest can establish semantic truth on its own.

The caller supplies an external bundle path, an independently reviewed exact
SHA-256 pin, and an artifact root. The scorer never reads a claimed pin from the
bundle or answer as authority. The bundle has this shape:

```json
{
  "protocol_version": 2,
  "change_id": "REST-001",
  "record_sha256": "<canonical full acceptance record SHA-256>",
  "snapshot_sha256": "<source snapshot SHA-256>",
  "artifacts": [{"path": "observations.json", "sha256": "<file SHA-256>"}],
  "attestations": [{
    "kind": "actions",
    "claim": {
      "repo": "order-service",
      "target": "com.example.AccountClient.AddressResponse.postalCode",
      "operation": "read_json_field",
      "before": "postalCode",
      "after": "postcode"
    },
    "artifact_paths": ["observations.json"]
  }]
}
```

`record_sha256` is SHA-256 over UTF-8 bytes of Python
`json.dumps(record, sort_keys=True, separators=(',', ':'), ensure_ascii=False)`.
It binds the entire record, including status, scope, proposed diff and alternative
plans. This canonical digest differs from a runner's raw record-file digest.
Changing or weakening a record requires a newly reviewed bundle and pin.

Every attestation must reference at least one content-hashed artifact beneath
the supplied root. Absolute paths, path escapes, escaping symlinks, stale
snapshots and modified artifacts fail closed. A valid empty or partial manifest
cannot qualify a full benchmark. The scorer checks the bundle's binding and
exact attestations; the independent verifier and its reviewed predicate mapping
are responsible for establishing what the execution actually proves. Merely
creating a new hash of self-authored claims is not approval.

## Reviewed equivalences and bounded scope, revision 2.1

An optional, independently pinned oracle can add **full-claim** equivalences:

```json
{
  "scoring_policy": {"mode": "scoped_reference_v1"},
  "equivalences": [{
    "kind": "actions",
    "alias": {
      "repo": "order-service",
      "target": "com.poc.order.infrastructure.client.AccountClient.address",
      "operation": "read_json_field",
      "before": "postalCode",
      "after": "postcode"
    },
    "canonical": {
      "repo": "order-service",
      "target": "com.poc.order.infrastructure.client.AccountClient.AddressResponse.postalCode",
      "operation": "read_json_field",
      "before": "postalCode",
      "after": "postcode"
    },
    "artifact_paths": ["independently-reviewed-method-binding-proof.json"]
  }]
}
```

These keys supplement the complete bundle, not the answer. Each alias and
canonical object contains every semantic field for its kind. Only `target`
for actions, `fqn` for symbols, or serialized `field` for edges may differ.
Repository, provider, consumer, direction, route/topic, contract type, operation,
and before/after values retain exact identity. Canonical claims must be actual
record expectations across its accepted alternatives. Artifact paths must bind
independently verified proof under the same full-bundle digest, source snapshot
and record digest checks. The independent verifier must establish the specific
method read mapping or generated accessor/serialization relationship before
certifying an alias. Identifier resemblance does not establish equivalence.

No general `#`, `()`, prefix, case, or fuzzy normalization is added. Self aliases,
redefinitions of expected claims, chained aliases and conflicting mappings fail
closed. Exact duplicate mappings and answer aliases deduplicate deterministically.
Untrusted, malformed, stale or unpinned bundles apply no aliases or scope policy.
`equivalences_applied` records each transformation and its proof artifact paths.
Attested aliases and answer aliases map to the same canonical predicate, so
equivalent source locations cannot inflate counts. Product receipts cannot
certify aliases. An answer-supplied equivalence or policy is not authority.

The independently verified method repair is one accepted way to express the
consumer JSON-binding predicate. It does not broaden the allowed change to a
producer rollback, wrong field, wrong consumer, or unverified repair strategy.
Reviewed method targets also identify exact `(repo, target, operation)` slots
for **negative** checks: adding a wrong before/after claim at that target is a
contradiction even when the correct canonical action is also present. This
negative check never grants wrong-field or wrong-operation positive credit.

`scoring_policy: {"mode": "scoped_reference_v1"}` is optional and trusted only
inside the validated oracle. It declares that the reference covers the frozen
scope, rather than enumerating every real symbol or every estate repository.
The report always labels precision as **reference overlap**. An unmatched
supporting symbol or out-of-scope repository is `extras_unverified`, not an
established false claim. It gains no verified correctness and does not establish
that an omitted repository is unaffected. Unmatched dependency edges also remain
unverified unless separate evidence establishes their truth; they earn no recall.

Two separate acceptance fields are reported for every alternative and attempt:

- `satisfies_required_actions`: all required actions from at least one coherent
  accepted alternative are present, after reviewed equivalences. Malformed
  action claims prevent this flag. This flag alone does not establish correct
  repository obligations or an entirely correct answer.
- `satisfies_any_repair`: the required action and in-scope obligation coverage
  from one alternative is complete, with no established in-scope contradictions
  or malformed claims. This establishes the bounded repair criteria only;
  unverified extra suggestions are not certified as correct.

Established contradictions include incompatible in-scope runtime/obligation
claims, observations opposite a known frozen observation, production edits in
independently established validation-only/unaffected repositories, and wrong
before/after values at a known required predicate slot. A missing requirement
is a miss. An unresolved or unlisted behavior is not automatically proven false.
For example, correctly identifying Order's read-mapping repair can satisfy
required actions while incorrectly treating the already-applied Account diff
as another required production edit fails complete in-scope obligations.

Scoped differences distinguish `missed`, established `false_positives`, and
`extras_unverified`; `contradictions` explains the established failures. Without
this trusted policy, historical strict matching and its reference false-positive
lists remain unchanged. Every revised score reports `evaluation_revision: "2.1"`.
The original scorer, schemas and report interpretation are preserved separately
under `harness/history/2026-10-18-original-v2/`; new policies and new oracle pins
belong to a new comparison cohort. An after-the-fact saved-answer diagnostic
must not replace the original attempt's score or be presented as a fresh run.

## Runner receipts and budgets

Deprecated static oracle-bundle `receipts` entries are
`{id, snapshot_sha256, artifact_path}`. The corresponding hashed artifact must
be JSON with matching `receipt_id` and `snapshot_sha256`. Answer receipt references
can fall back to these only when no per-run receipt envelope is supplied. Live
invocation IDs are created during execution and **must not** require rewriting
the pre-reviewed semantic oracle.

Live receipts use a separate runner-owned envelope, saved outside model access:

```json
{
  "protocol_version": 2,
  "change_id": "REST-001",
  "snapshot_sha256": "<source snapshot SHA-256>",
  "run_started_at": "<runner timestamp>",
  "prompt_sha256": "<runner prompt SHA-256>",
  "estate_sha256": "<runner estate content SHA-256>",
  "budget_policy": {
    "timeout_seconds": 300,
    "max_tokens": 1000000,
    "max_cost_usd": 10,
    "cost_enforcement": "catalog_context_reservation",
    "token_enforcement": "context_window_reservation",
    "retries": false,
    "compaction": false
  },
  "usage": {
    "tokens_consumed": 300,
    "cost_usd": 0.003,
    "elapsed_seconds": 1.25
  },
  "receipts": ["<actual ProductInvocationReceipt objects>"],
  "product_provenance": null,
  "product_playbook": null
}
```

The runner supplies a separately trusted SHA-256 over exact envelope bytes.
Schema and identity checks bind it to the record and runner-stamped answer,
including run time, prompt, estate, provenance, playbook and retained receipt
list. The explicit `budget_policy` is required on live envelopes and runner-saved
answers. The policy object has exactly the seven fields shown above; token limits
are positive integers, time/cost limits are positive finite numbers, and the
enforcement values and disabled retries/compaction are fixed. Its entire value
must match the runner-stamped answer. The envelope's `usage` must also match the
answer's top-level `tokens_consumed`, `cost_usd` and `elapsed_seconds` exactly.
Usage tokens are nonnegative integers; usage cost and time are nonnegative
finite numbers. Missing, altered, nonfinite or negative telemetry cannot be
rescued by rehashing the answer. This binds reported SDK/catalog telemetry; it
does not prove an external billing invoice or hardware timing measurement.

The runner reserves the unchanged model context and full model output allowance
before a public SDK request, using catalog input/cache/output rates, and disables
automatic retries and compaction. The scorer verifies the declared policy and
usage binding; runner enforcement and aggregation's over-cap eligibility checks
remain independent gates. A budget-exhausted attempt is a saved failed attempt,
not a fabricated correctness result. Valid usage changes never alter the
correctness metrics. Static scorer unit/diagnostic inputs without a live runner
timestamp can omit the policy; real runner-saved answers cannot qualify without
a trusted live envelope.

IDs must be unique; a cited receipt must have succeeded. Successful
receipts retain output text, matching byte counts and normalized text whose
SHA-256 is recomputed. The raw `output_sha256` is checked for the actual product
format (lowercase 64-digit SHA-256). It hashes the **entire raw MCP result** in
`products.ts`, which the receipt does not retain; the scorer cannot reconstruct
that raw digest from the retained first text block. The trusted runner envelope
provides the raw-result hash binding.

The Python API accepts `run_receipt_bundle_path` and
`trusted_run_receipts_sha256`; the CLI accepts `--run-receipts PATH` and
`--trusted-run-receipts-sha256 SHA`. A supplied run envelope takes precedence
over deprecated static receipt references; it cannot be rescued by a static
oracle receipt. No references produce `not_supplied` attribution, while unknown,
missing, failed or cross-run references fail provenance. A valid receipt proves
attribution only, never semantic correctness or successful participation of every
required product step. The runner retains responsibility for product-first
participation eligibility.

`evidence.<kind>.verified_correct` counts **only exact canonical structured predicates**
that agree with the record and trusted attestations, including independently
proved aliases recorded in `equivalences_applied`. It does not certify the
truth of arbitrary attached prose or unrelated citations. Unsupported claims
stay `unverified`.

## Commands and reporting

From the study root:

```sh
python3 harness/scoring/score_v2.py validate --kind record /path/record.json
python3 harness/scoring/score_v2.py validate --kind answer /path/answer.json
python3 harness/scoring/score_v2.py preflight --record /path/record.json \
  --oracle-bundle /trusted/bundle.json --trusted-oracle-sha256 REVIEWED_SHA \
  --artifact-root /trusted/artifacts
python3 harness/scoring/score_v2.py score --record /path/record.json \
  --answer /path/answer.json --oracle-bundle /trusted/bundle.json \
  --trusted-oracle-sha256 REVIEWED_SHA --artifact-root /trusted/artifacts
python3 harness/scoring/test_score_v2.py
```

`validate --record PATH` remains supported. Validation checks syntax and record
consistency, not runtime truth. Preflight requires a verified record, common
expectation coverage, and at least one completely attested repair alternative
before model execution. Scoring without a trusted bundle is supported only as
a diagnostic. Eligible incorrect answers remain measurable incorrect outcomes;
benchmark eligibility is not an accuracy claim. Required product invocation,
run integrity and v2 cohort aggregation remain runner gates.

Metrics report exact canonical reference precision/recall by claim kind, origin/downstream separation,
affected downstream runtime observations, and production-edit/validation-only/
unaffected/unresolved obligations. Critical downstream misses are explicit.
No tokens, elapsed time, usage floors, latency weights, or composite score enter
correctness. Missing expected sets have recall 1; empty reports have precision 1
only when the expected set is also empty.

## Verification recorded during implementation

Before introducing the v2 scorer, three desired assertions were run through the
public v1 `score_change` interface. They failed as expected: wrong-repository
symbol recall was **1.0**, wrong-provider/consumer contract recall was **0.5**,
and unsupported prose evidence quality was **0.8571**. These establish the old
loopholes; v1 behavior remains unchanged for historical reproducibility.

The v2 suite uses reviewed literal expectations, 30 seeded generated cases per
property family, a small quadratic reference checker, and bounded targeted
fault injection. Mutations actually replace repository matching, consumer
matching, schema validation, oracle trust, receipt validation and nonempty-prose
trust behavior; each is caught by its dedicated assertion. Content/snapshot/
record tampering and weaker-subset reuse are rejected. This tests the scorer's
verification boundary; it is not a substitute for executing the estate's real
HTTP/Kafka/PostgreSQL boundaries.

Revision 2.1 adds source-variant, directed-field, scope-extras, action-versus-full-
repair and protected-identity regressions. Tests reject changed repository,
consumer, direction, operation, before/after values, unlisted canonical targets,
conflicting/chained aliases and broken proof bindings. A correct action plus a
wrong-field claim at a reviewed method alias still fails full repair. Supporting
extras remain unverified, while canonical/alias duplicates give one hit. These
unit tests exercise the trust boundary; the independent verifier separately
establishes whether each proposed source mapping is actually equivalent.
