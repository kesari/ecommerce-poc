import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const HARNESS = resolve(dirname(fileURLToPath(import.meta.url)), "..");
export const RUN_DIR = join(HARNESS, "run");
export const RECORDS = join(HARNESS, "records");
export const RUNS = join(HARNESS, "answers", "runs");
export const SCORER = join(HARNESS, "scoring", "score.py");
export const AGGREGATOR = join(RUN_DIR, "aggregate.py");
