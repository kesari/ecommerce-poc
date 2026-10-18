import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const HARNESS = resolve(dirname(fileURLToPath(import.meta.url)), "..");
export const RUN_DIR = join(HARNESS, "run");
export const RECORDS = join(HARNESS, "records");
export const RUNS = join(HARNESS, "answers", "runs");
export const SCORER = join(HARNESS, "scoring", "score.py");
export const SCORER_V2 = join(HARNESS, "scoring", "score_v2.py");
export const AGGREGATOR = join(RUN_DIR, "aggregate.py");
export const AGGREGATOR_V2 = join(RUN_DIR, "aggregate_v2.py");
