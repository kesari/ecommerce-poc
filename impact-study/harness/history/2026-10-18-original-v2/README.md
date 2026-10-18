# Original v2 scoring replay

This immutable snapshot preserves the original scorer, schema, aggregation,
prompt and REST record before the repair-equivalence fixes. The original model
answers, scores and receipt files remain at their original paths. `manifest.json`
contains byte hashes; do not update these files to follow the current scorer.

From the study root, the original scorecard can be reproduced without a model:

```sh
python3 harness/history/2026-10-18-original-v2/run/aggregate_v2.py \
  --record harness/history/2026-10-18-original-v2/records/v2/REST-001.json \
  --oracle-bundle harness/verification/evidence/REST-001/oracle-bundle.json \
  --trusted-oracle-sha256 33b42c1fca9396918b35c584076cca9cb5351ceb09d59889d5c195fd050d12d6 \
  --artifact-root harness/verification/evidence/REST-001 \
  --runs-dir harness/answers/runs/v2
```

The replay has been checked: two scored answers and one retained deadline
failure. Reassessments under the corrected scorer and new provider runs belong
to separate directories and must not replace these original score reports.
