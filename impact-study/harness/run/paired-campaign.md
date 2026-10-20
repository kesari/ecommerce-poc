# Frozen paired campaign format

`aggregate_paired.py` is a pure offline evaluator for revision 2.2. It never calls
a model or product. Existing single-case aggregators and historical scores remain
separate. An empty ledger is valid preparation evidence and means all 144 cells
are unrun. `paid_runs_authorized: false` remains valid for offline preparation;
the runner independently refuses provider initialization under that condition.

## Frozen inputs

The case manifest has `campaign_version: 1` and twelve `cases`. Each row contains:

```json
{
  "case_id": "H01",
  "family": "direct_contracts",
  "record_path": "path/relative/to/artifact-root/H01.json",
  "record_sha256": "<sha256>",
  "oracle_bundle_path": "path/relative/to/artifact-root/oracle.json",
  "oracle_bundle_sha256": "<independently-reviewed-sha256>",
  "artifact_root": "path/relative/to/artifact-root/proof-directory",
  "compatible_control": false
}
```

There must be six families containing two cases each and at least two compatible
controls. Control classification is frozen before predictions. Records and every
oracle are independently preflighted even when no model attempts exist.

Conditions have `campaign_version: 1`, `protocol_version: 2`,
`evaluation_revision: "2.2"` and an explicit `paid_runs_authorized` boolean.
`campaign_budget` contains an explicit `authorization_id`, `currency: "USD"` and
finite `max_total_usd`. Unpaid preparation may use a zero cap; paid authorization
requires a positive cap. This field records authorization and never grants it.
`shared_identity` contains `model`, `model_digest`, `pi_version`, `thinking`,
`harness_sha256`, `estate_sha256`, `scorer_sha256`, `answer_schema_sha256`,
`task_template_sha256`, `scoring_policy` (the oracle's mode string), and
`budget_policy`. A remote model's unavailable immutable weight digest is explicit
`null`; it is never fabricated. Every arm has the same shared policy and attempt
ceilings.

`arms` is keyed by four runner IDs: one `treatment: "baseline"`, three
`treatment: "product-first"`. Each arm contains `contestant`,
`contestant_config_sha256`, `index_sha256` and `prompt_sha256_by_case`. Baseline
index identity is `"none"`; product index identities are actual frozen hashes.

Prompt hashes in conditions identify the **public task prompt before native
playbook results are appended**. Each case must have the identical public task
prompt hash across all four arms; any task drift is rejected. The protected runtime
envelope separately binds the effective prompt containing that attempt's actual
native results. Effective prompts and arm-specific configuration/index hashes
must not be compared for equality between treatments.

`analysis` freezes `bootstrap_seed`, `bootstrap_samples` (normally 10000) and
`decision_gain: 0.10`.

`build_schedule(cases, conditions, cases_manifest_sha256=...,
condition_sha256=..., seed=...)` returns 144 cells: twelve cases, four arms and
three repetitions. It shuffles case order for each repetition and rotates arm
order within cases. Every cell has `slot_id`, `case_id`, `repeat`, `arm`,
`ordinal`; slot IDs are `H01/r1/<runner-id>`. The schedule stores both input
digests, its seed and complete ordered cells. Analysis reconstructs this schedule
and rejects any divergence.

The CLI `schedule` accepts `--cases`, `--trusted-cases-sha256`, `--conditions`,
`--trusted-condition-sha256` and `--seed`. Save its JSON output, review it and pass
its externally recorded digest to the runner and evaluator.

## Trusted execution ledger

Runtime answers cannot assign their own scheduled slot. An out-of-band recorder
captures the runner's measurement identity and artifact paths. Review and seal
that ledger independently, then supply its SHA-256 explicitly to analysis.
`seal_execution_ledger(schedule_pin, condition_pin, recorder_entries,
artifact_root, campaign_budget_ledger=...)` hashes artifacts from those already trusted recorder facts; it
does not derive trusted identity from answer content and does not authorize runs.

The ledger contains `campaign_version: 1`, `schedule_sha256`,
`condition_sha256`, and `entries`. Any nonempty execution ledger also requires
`campaign_budget_ledger: {"path": "...", "sha256": "..."}` pointing to an
independently sealed copy of the durable global budget ledger. An empty offline
ledger may omit it; overall exposure then remains unknown. A completed entry has exactly:

```json
{
  "slot_id": "H01/r1/<runner-id>",
  "measurement_identity": "<runner-authored identity object>",
  "run_started_at": "<runner start identity>",
  "campaign_budget_reservation_id": "<trusted global reservation UUID>",
  "status": "completed",
  "answer": {"path": "runs/answer.json", "sha256": "<sha256>"},
  "score": {"path": "runs/answer.score.json", "sha256": "<sha256>"},
  "receipts": {"path": "runs/receipts/envelope.json", "sha256": "<sha256>"}
}
```

A failed entry has the same identity and reservation fields, `status: "failed"`, `failure`
pointing to the runner rejection stub and `attempt` pointing to its protected
`.attempt.json`. Each pointer has `path` and `sha256`. Paths are relative to the
supplied artifact root; path traversal and symlinks are rejected. Slot IDs,
runner/start identities and reused answer/receipt/attempt digests cannot occur
twice. A scheduled cell without an entry stays `missing_unrun`.

The reservation UUID comes from the protected attempt's
`campaign_budget_reservation.reservation_id`, not model output. Analysis binds it
to the durable row's exact slot/start identity. It recomputes conservative USD
totals using the runner's nano-dollar ceiling rules and checks the authorization
ID, immutable cap and currency. Previous schedules sharing the same authorization
retain their debit/exposure. Zero-settled preflight reservations do not invent
model attempts. Pending/crashed reservations and unknown or unrecorded exposure
remain visible and block a final benefit verdict, as do cost-cap breaches and
observed paid cost under `paid_runs_authorized: false`.

The measurement identity binds runner, contestant, run index (= repetition),
model/digest, SDK version, harness, estate, arm configuration, index, thinking,
public task prompt and `campaign_slot`. The slot binds schedule/conditions hashes,
case, repetition and arm. Its frozen target index remains the intended treatment
identity on setup failures; actual index equality and participation must be
verified before a successful product-assisted answer can count. Failure identity
does not assert that a missing product or model actually ran.

Analysis compares this identity with the trusted frozen slot, protected envelope
and stamped answer. It revalidates product participation, independently reruns the
current scorer and checks the stored verdict and hashes. Neither a self-reported
receipt digest nor a self-reported model identity establishes trust. Corrupted
artifacts become retained `verification_rejected` observations and block benefit
claims; duplicate or off-schedule ledger entries fail campaign preflight.

The CLI `analyze` accepts `--schedule`, `--trusted-schedule-sha256`,
`--conditions`, `--trusted-condition-sha256`, `--cases`, `--ledger`,
`--trusted-ledger-sha256`, and `--artifact-root`.

## Report interpretation

Every scheduled cell remains in the output. Scheduled, invoked, valid answers,
observed failures and missing/unrun counts remain distinct. Missing cells are
unknown outcomes, never observed model mistakes. Scheduled success fractions are
accompanied by bounds allowing every missing cell to succeed. Attempted operational
success includes setup, provider, budget and invalid-output failures. Correctness
conditional on valid answers is also reported.

Pairs share `(case_id, repeat)`; cost, elapsed time and token use never determine
pairing. Reports show product-only and baseline-only successes, successful/failed
ties, unknown pairs, and pairs where both answers were valid. They average three
paired differences within each case and weight the twelve cases equally. Partial
case means are descriptive and explicitly separated from complete-case estimates.
Scheduled paired-difference bounds retain missing outcomes.

Seeded percentile bootstrap intervals resample intact cases, preserving their
repetitions and matched treatment arms. A separate sensitivity interval resamples
the six family means. Family outcomes are also listed. These are exploratory
intervals across three product comparisons, not multiplicity-adjusted formal
significance claims or evidence that repetitions are independent scenarios.

The practical correctness rule requires a complete campaign, at least 10 percentage
points of absolute improvement, a case-cluster interval above zero and no observed
compatible-control safety regression. Every compatible-control pair must also
have two valid answers; missing control safety blocks a benefit verdict. A
non-passing rule means no demonstrated practical benefit, not equivalence.
Efficiency alone does not create a correctness or equivalence claim.

Action/edge component metrics, critical misses, unsupported material claims,
independently disproved edit claims, compatible-control specificity and native
operation failures remain separate. Unverified extra claims are not automatically
false. Setup/index construction requires its own evidence and is not included in
attempt cost; no zero setup cost is inferred. Runtime usage and unknown pending
cost exposure are accounted separately and globally deduplicated. Cost totals
explicitly cover sealed observed attempts only: an unrecorded slot has no cost
evidence, and zero known cost does not prove zero overall campaign exposure.
The out-of-band recorder must reconcile atomic invocation claims and durable
budget reservations when a process dies before producing final artifacts. Trusted receipt
cost remains visible even if its associated answer fails verification.

Run the offline checks with `python3 -m unittest test_aggregate_paired -v` from
`harness/run`. All campaign fixtures are temporary unit-test data; they are not
saved model runs or benchmark results.
