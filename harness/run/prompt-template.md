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

Classify the evidence for every finding:

- `compiler` — a compiler or index proves the reference
- `extracted` — read directly out of source or configuration
- `contract_matched` — a provider and a consumer of the same REST or event contract
- `inferred` — reasoned from surrounding context
- `hypothesis` — a guess worth investigating

## Output

Reply with ONE JSON object and nothing else. No prose, no markdown fence.

```
{
  "change_id": "{{CHANGE_ID}}",
  "contestant": "agent-only",
  "findings": {
    "repositories": [ {"name": "...", "evidence_tier": "...", "evidence": "..."} ],
    "symbols":      [ {"fqn": "...", "repo": "...", "evidence_tier": "...", "evidence": "..."} ],
    "contracts":    [ {"type": "rest|kafka|grpc|openapi|db", "identifier": "...",
                       "consumer_repos": ["..."], "evidence_tier": "...", "evidence": "..."} ],
    "tests":        [ {"repo": "...", "suite": "...", "evidence_tier": "...", "evidence": "..."} ]
  }
}
```

## Length limit

Keep every `evidence` value to ONE short sentence, at most 160 characters,
naming the file and line that proves it. Long evidence strings overflow the
response limit and truncate the JSON, which scores as a total failure.

Report only what the estate supports. A missed dependency and a fabricated one
both count against you.
