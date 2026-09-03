// Stage: hand the answer to the Python scorer. Schema validation gates scoring,
// so a malformed answer never gets a score and never reaches the scorecard.
import { spawnSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import { AGGREGATOR, SCORER } from "./paths.ts";

function python(script: string, args: string[]) {
	const result = spawnSync("python3", ["-B", script, ...args], { encoding: "utf8" });
	if (result.error) throw result.error;
	return result;
}

export function validateFile(kind: "record" | "answer", path: string) {
	const result = python(SCORER, ["validate", "--kind", kind, path]);
	if (result.status !== 0) {
		throw new Error(`${kind} schema validation failed: ${(result.stdout || result.stderr).trim().slice(0, 1200)}`);
	}
}

/** Writes `<answer>.score.json` and returns the readable summary the scorer prints. */
export async function scoreAnswer(answerPath: string, recordPath: string) {
	const reportPath = answerPath.replace(/\.json$/, ".score.json");
	const result = python(SCORER, [
		"score", "--ground-truth", recordPath, "--answer", answerPath, "--output", reportPath,
	]);
	if (result.status !== 0) {
		throw new Error(`scoring failed: ${(result.stderr || result.stdout || "").trim().slice(0, 1200)}`);
	}
	const report = JSON.parse(await readFile(reportPath, "utf8"));
	return { report, text: result.stdout.trimEnd() };
}

/** Corpus scorecard across every scored run, including runs from earlier sessions. */
export function aggregate() {
	const result = python(AGGREGATOR, []);
	return (result.status === 0 ? result.stdout : result.stderr || result.stdout).trimEnd();
}
