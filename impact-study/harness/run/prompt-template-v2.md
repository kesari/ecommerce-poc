Analyze the proposed change across the repositories available to you. Report
what behavior changes, what downstream edits are necessary, and which
repositories need validation without production edits. Use only source and
product evidence available to this run. The proposed change is assumed applied;
do not count merely implementing that supplied diff as downstream remediation.

Evaluation policy shared by every task: assess Java HTTP clients, normal first
message delivery through registered listeners, proxy forwarding, web data
consumption, and private SQL/cache boundaries. Assume required routes and broker
topics are provisioned and supplied migrations are applied. Deployment and
mixed-version rollout, topic provisioning, security/authentication, retries and
DLQ handling are outside the requested behavior. Put recommendations for those
surfaces in additional_recommendations, rather than structured repair claims.
Runtime status concerns compatibility under the supplied change: the intended
new behavior itself is not a break. Leave unestablished behavior unresolved.

Question: {{QUERY}}

Origin repository: {{CHANGE_REPO}}

Repository inventory: {{REPOSITORY_UNIVERSE}}
Use repository IDs from this inventory for repo, provider and consumer;
class and method names belong in symbols and action targets.
For an unrelated estate with IDs `widget-service` and `report-service`, an edge
could read `{ "provider": "widget-service", "consumer": "report-service",
"type": "rest", "identifier": "GET /widgets/{widgetId}", "field": "code" }`.
Those example IDs and that example contract are not findings for this task.

```diff
{{CHANGE_DIFF}}
```

Report causal dependencies only when you can support their direction, contract,
and relevant field or topic. Identifier similarity alone does not establish a
dependency or a required edit. Distinguish an observed or supported failure
from a possible consequence. Use `unresolved` when evidence is insufficient.
When the question explicitly limits the evaluated boundary, keep structured
runtime statuses and obligations within that boundary. Put uncertainties about
excluded behavior in an optional top-level `limitations` array of strings;
excluded behavior has not been established as either broken or unchanged.
Put useful recommendations beyond the explicitly evaluated behavior, such as
additional documentation or rollout work, in an optional top-level
`additional_recommendations` array of strings. Keep those separate from the
repair obligations needed to restore the behavior evaluated by this question.

Choose one coherent repair strategy preserving the requested upstream change.
Do not combine mutually exclusive strategies. A broken downstream behavior may
be restored by repairing its upstream input; that does not establish that every
downstream implementation needs editing. Runtime statuses describe the state
after the proposed change and before your suggested repair. Obligations describe
your chosen repair strategy.

Produce one JSON object with `protocol_version: 2`, `change_id: "{{CHANGE_ID}}"`,
and `findings` containing these five arrays:

- `repositories`: `{repo, runtime_status}` with status `broken`, `unchanged`, or
  `unresolved`. Describe the origin separately from downstream repositories.
- `obligations`: `{repo, obligation}` where obligation is `production_edit`,
  `validation_only`, `unaffected`, or `unresolved` under your repair strategy.
- `symbols`: `{repo, fqn}`. Include the exact declaring member for a member-level
  claim. Use baseline-source dotted names, such as
  `org.example.payments.Reader.Result.code`, with nested types separated by dots.
  A record component uses its component name without `#` or trailing `()`.
  A shared class prefix does not prove a member-level claim. Keep supporting
  callers in evidence rather than trying to enumerate every nearby symbol.
- `edges`: `{provider, consumer, type, identifier, field}`. Type is `rest`,
  `kafka`, `grpc`, `db`, or `local`. Use `METHOD /path` for REST identifiers and
  the exact topic for Kafka. Edge identity describes the ORIGINAL contract
  before the supplied diff: use its original topic and serialized field, or an
  empty `field` when no field-specific claim is made. For an enveloped message,
  field paths are relative to the business payload, with arrays written like
  `lines[].code` (omit the outer envelope prefix). Actions describe the
  transition from the original to the requested new contract. Provider -> consumer is the direction
  of data supplied, even if the consumer initiates the HTTP call.
- `actions`: `{repo, target, operation, before, after}`. Use the exact baseline
  declaring member or contract location implementing the repair as target,
  with dotted names as above, and the old/new contract values. For
  `read_json_field`, before and after are the local serialized member keys at
  the named read location, rather than whole envelope paths. An equivalent
  method-level read mapping may name the method that implements that mapping.
  Public operations are `read_json_field`, `subscribe_topic`, `publish_topic`,
  `retain_json_alias`, and `scale_numeric_field`. For `scale_numeric_field`,
  before and after are positive integer multiplier strings applied at the
  target (for example, an unrelated conversion from dozens to units uses
  before `1` and after `12`).
  Describe an equivalent alias/read-mapping repair as `read_json_field`; the
  predicate concerns behavior rather than one particular patch implementation.

Each claim may include a short `evidence` explanation, `citations` as objects
`{path, line?, sha256?}` naming source locations, and a `receipt_id` when a product result supports it. A matching
receipt proves the product returned data, not that the interpretation is true.
Do not invent oracle or expected-answer identifiers. The runner supplies source
snapshot metadata independently.

Use an empty array when you have no supported claims of a kind. Keep claims
precise and concise. Return JSON only, with no surrounding prose or fence.
