# Prepared comparison; provider execution disabled

This is the current revision 2.2 offline freeze. `conditions.json` has
`paid_runs_authorized: false` and a zero total allowance under the pending
approval identifier. The US$10 per-attempt cap is a proposed configuration,
not an approved spend allowance. No prior smoke allowance transfers here.

- `frozen-input-pins.json` records the reviewed case, condition, schedule and
  empty execution-ledger digests.
- `schedule.json` contains 144 ordered case/repetition/arm slots. Case order is
  seeded; starting-arm order rotates separately for each case.
- `pre-run-analysis.json` independently checks all twelve oracles and reports
  every slot as missing/unrun, with no correctness verdict or product winner.
  It does not infer zero total exposure merely from an empty results ledger.
- `disabled-cli-check.json` retains an actual CLI launch rejected for missing
  paid authorization before a provider could initialize.
- `preparation.json` retains source and native-product provenance. The current
  Gortex native receipt is adapter 1.4.2; unchanged RepoWise/Codebase Memory
  operations retain their preceding 1.4.1 coverage evidence. Their source and
  indexed-estate hashes match the freeze.

See `harness/run/paired-campaign.md` for the exact runner/ledger format. The
planned eight paid development checks have not run. If those checks require
source/prompt changes, create a fresh freeze before predicting comparison cases.
Do not edit a frozen file in place: authorization and budget changes require
new condition/schedule digests, while the durable budget authorization ID must
retain all prior exposure under that allowance.
