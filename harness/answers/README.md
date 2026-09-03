# Contestant answers

- `runs/` — real contestant output written by `../run/run.ts`. One file per
  (record, contestant, run index). A `.score.json` is created only after the
  answer passes schema validation; aggregation rejects stale scores paired with
  invalid answers.
- `examples/` — hand-written illustrations of the answer format, flagged
  `"synthetic": true`. They are documentation, not evidence, and must never
  appear in a scorecard.
- `rejected/` — preserved real outputs that failed validation. They diagnose
  prompt and schema problems but never enter the scorecard.

Anything without `"synthetic": true` under `runs/` is a real measurement.
