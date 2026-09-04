You are analysing a multi-repository Java/Spring microservice estate to answer a
change-impact question. Every subdirectory of the directory you are given is an
independent Git repository.

## Question

{{QUERY}}

## Proposed change

Repository: {{CHANGE_REPO}}

```diff
{{CHANGE_DIFF}}
```

## What to produce

Determine the complete blast radius: every repository, symbol, contract, and test
suite the change affects.

A reference to the same identifier is not automatically an impact. The same field
name can belong to a different contract entirely, in which case the repository
holding it is unaffected. Decide which contract each reference belongs to before
including it.

Follow values across service boundaries, not just names. A field renamed at
the provider can arrive as null at a consumer that binds by name, and that
null can travel inside an unchanged event envelope until a downstream guard
rejects it. Trace the runtime data flow through clients, snapshots, events,
listeners, and validators before ruling a downstream repository unaffected.

Verify pass-through claims by reading the relay code. A BFF controller that
relays an untyped JSON node without naming the field is genuinely outside the
blast radius of a field rename; a controller or spec that names the field is
inside it. Never include or exclude a relay on assumption.

Check the per-service contract directories: `openapi/` for REST schemas and
`asyncapi/` plus `kafka/topic-definitions.yaml` for event topics and their
consumers. Prefer these documents over guessing endpoint or topic names.

A contract whose only consumer is its own provider is not cross-repository
impact. Omit it unless another repository consumes it.

Classify the evidence for every finding:

- `compiler` — a compiler or index proves the reference
- `extracted` — read directly out of source or configuration
- `contract_matched` — a provider and a consumer of the same REST or event contract
- `inferred` — reasoned from surrounding context
- `hypothesis` — a guess worth investigating

When a real product query tool is available, also set `attribution` on each
finding: `product_direct` only when the product output contains the fact,
`agent_inferred` when you bridge or reason beyond that output, and `file_search`
when the read/grep/find/ls tools establish it.

## Identifier convention

Use these canonical forms so answers can be matched. Matching is
case-insensitive; `asyncapi` is accepted as an alias of `kafka`.

- `rest` — `METHOD /path`, for example `POST /api/v1/addresses`
- `openapi` — `openapi:<file>#/components/schemas/<Name>`, for example
  `openapi:account.yaml#/components/schemas/AddressResponse`
- `kafka` — `<topic>#<json-pointer>`, for example
  `order.confirmed.v1#/address/postalCode`
- `grpc` / `db` — fully qualified: `<service>.<table>.<column>`, for example
  `account.addresses.postal_code`. Table-only names do not match.

Report the declaring type for symbols: `com.example.Foo`, not
`com.example.Foo.field`. A field reference still credits its type.

Report the short test class name for suites: `DeliveryEstimatorTest`, not
`com.poc.shipment.DeliveryEstimatorTest` and not a file path. The suite is
the class under `src/test` that covers the finding.

## Output

Reply with ONE JSON object and nothing else. No prose, no markdown fence.

```
{
  "change_id": "{{CHANGE_ID}}",
  "contestant": "agent-only",
  "findings": {
    "repositories": [ {"name": "...", "evidence_tier": "...", "attribution": "product_direct|agent_inferred|file_search", "evidence": "..."} ],
    "symbols":      [ {"fqn": "...", "repo": "...", "evidence_tier": "...", "attribution": "product_direct|agent_inferred|file_search", "evidence": "..."} ],
    "contracts":    [ {"type": "rest|kafka|grpc|openapi|db", "identifier": "...",
                       "consumer_repos": ["..."], "evidence_tier": "...", "attribution": "product_direct|agent_inferred|file_search", "evidence": "..."} ],
    "tests":        [ {"repo": "...", "suite": "...", "evidence_tier": "...", "attribution": "product_direct|agent_inferred|file_search", "evidence": "..."} ]
  }
}
```

## Length limit

Keep every `evidence` value to ONE short sentence, at most 160 characters,
naming the file and line that proves it. Long evidence strings overflow the
response limit and truncate the JSON, which scores as a total failure.

Search before answering. Use the read, grep, find, and ls tools to inspect
every repository that could be affected; cite only files you actually opened.
An answer produced without searching the estate is invalid.

Report only what the estate supports. A missed dependency and a fabricated one
both count against you.
