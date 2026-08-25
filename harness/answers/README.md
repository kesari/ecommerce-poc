# Contestant answers

- `runs/` — real contestant output written by `../run/run.sh`. One file per
  (record, contestant, run index), each with its `.score.json` beside it.
- `examples/` — hand-written illustrations of the answer format, flagged
  `"synthetic": true`. They are documentation, not evidence, and must never
  appear in a scorecard.

Anything without `"synthetic": true` under `runs/` is a real measurement.
