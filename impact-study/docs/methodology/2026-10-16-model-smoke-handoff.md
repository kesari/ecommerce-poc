# Budgeted model smoke handoff

Date: 2026-10-16. Updated: 2026-10-18. Status: outbound-context approval received;
the budgeted smoke sequence stopped conservatively after a host interruption.
Authorized total allowance: US$5 for one agent-only run and one run per available
active product. Calls and costs for the 18 October sequence are recorded in the
[campaign ledger](../../harness/answers/campaigns/2026-10-18-v2-rest001-smoke.json).
The 16 October blocked command made no provider calls and incurred no spend.

## Concrete proposed execution

The selected provider is `openai-codex`, model `gpt-5.6-terra`, API adapter
`openai-codex-responses`. The installed SDK's local catalog resolves its base
URL to `https://chatgpt.com/backend-api`. This was read locally with networking
disabled and `modelsPath: null`; credentials were not printed. The request uses
the existing local Codex authentication and SSE transport, with automatic retry,
transport fallback and compaction disabled.

The model receives the neutral v2 task, the public proposed change, read-only
tool definitions, and source excerpts returned from the isolated ten-repository
fixture estate at `/Users/rajmohan/Projects/POC/POC-order-microservices`.
Product-assisted runs additionally supply product-native query results over the
same fixture repositories. The allowlisted fixture repositories are Account,
Basket, Catalog, commerce BFF/platform/web, Inventory, Order, Payment and Shipment.
The independent oracle, expected findings, reference repairs and verifier logs
are outside that searchable estate and are not supplied to the prediction agent.

The first requested command, from `harness/run/`, was:

```sh
node run.ts --protocol 2 --record REST-001 --contestant pi-codex --runs 1 \
  --max-tokens 2000000 --max-cost-usd 5 --timeout 300 \
  --oracle-bundle ../verification/evidence/REST-001/oracle-bundle.json \
  --trusted-oracle-sha256 33b42c1fca9396918b35c584076cca9cb5351ceb09d59889d5c195fd050d12d6 \
  --artifact-root ../verification/evidence/REST-001
```

After that run, product smokes would use `pi-codex-gortex-first`,
`pi-codex-repowise-first`, and `pi-codex-codebase-memory-first`, one at a time.
Each next `--max-cost-usd` must be the remaining total allowance after all prior
observed costs and any unsettled request exposure. The CLI option itself is a
per-attempt ceiling, not a campaign allowance. Do not run all contestants at a
fresh US$5 ceiling each. These integration smokes cannot support a tool ranking.

The pinned model advertises a 272,000-token context and 128,000-token maximum
output. Its worst catalog tier gives a conservative US$3.664 full-request
reservation. The adapter ignores a smaller output cap, so the harness reserves
the full advertised output. Insufficient remaining budget stops before a new
request. The assumptions are provider compliance with advertised limits and
catalog rates, rather than a guarantee of the eventual invoice.

## Approval-review block

The tool's automatic approval review rejected the exact command before creating
the process. It identified potential transmission of repository-derived context
to an external destination and said that the budget authorization did not
explicitly approve the sensitive payload and destination. No alternative or
indirect provider invocation was attempted after rejection. This is an execution
approval block, not a scored benchmark or model failure.

On 18 October the user explicitly approved sending this fixture source context
and native product results to the stated OpenAI Codex endpoint for these smokes,
keeping the US$5 total ceiling. The baseline command then passed automatic
approval review and started. Local gates and native adapter smokes are complete.
The [18 October results](2026-10-18-v2-smoke-results.md) record two scored answers,
one sleep-interrupted attempt and the budget-based decision not to start the
fourth model run. No additional paid retry was launched.
