# Rejected contestant output

These files preserve real model output that failed harness validation. They are
diagnostic artifacts, not benchmark evidence, and are excluded from aggregation.

`REST-001-pi-qwen3-coder-1.json` uses the unsupported contract type `asyncapi`.
Its `pre-validation-score.json` companion was produced by the earlier runner
before score-time schema enforcement existed and must not be included in a
scorecard.
