// Stage: hand the answer to the Python scorer. Schema validation gates scoring,
// so a malformed answer never gets a score and never reaches the scorecard.
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { readFile, writeFile } from "node:fs/promises";
import { AGGREGATOR, AGGREGATOR_V2, HARNESS, SCORER, SCORER_V2 } from "./paths.ts";
import { join } from "node:path";

export interface V2OracleOptions {
	oracleBundle: string;
	trustedOracleSha256: string;
	artifactRoot: string;
	runReceiptBundle?: string;
	trustedRunReceiptsSha256?: string;
}

function protocol(path: string) {
	const version = JSON.parse(readFileSync(path, "utf8")).protocol_version ?? 1;
	if (version !== 1 && version !== 2) throw new Error(`unsupported protocol version: ${version}`);
	return version;
}

function oracleArgs(options?: V2OracleOptions) {
	if (!options?.oracleBundle || !options.trustedOracleSha256 || !options.artifactRoot) {
		throw new Error("v2 requires an independent oracle bundle, explicit trusted SHA-256, and artifact root");
	}
	return ["--oracle-bundle", options.oracleBundle, "--trusted-oracle-sha256", options.trustedOracleSha256, "--artifact-root", options.artifactRoot];
}

function python(script: string, args: string[]) {
	const result = spawnSync("python3", ["-B", script, ...args], { encoding: "utf8" });
	if (result.error) throw result.error;
	return result;
}

/**
 * Structural validation only. A single malformed finding is not fatal: the scorer
 * drops it and charges the contestant for it, rather than voiding the whole run.
 */
export function validateFile(kind: "record" | "answer", path: string) {
	const result = python(protocol(path) === 2 ? SCORER_V2 : SCORER, ["validate", "--kind", kind, path]);
	if (result.status !== 0) {
		throw new Error(`${kind} schema validation failed: ${(result.stdout || result.stderr).trim().slice(0, 1200)}`);
	}
}

/** Runs before provider initialization; the prediction agent never receives oracle inputs. */
export function preflightV2(recordPath: string, options: V2OracleOptions) {
	const result = python(SCORER_V2, ["preflight", "--record", recordPath, ...oracleArgs(options)]);
	if (result.status !== 0) throw new Error(`v2 oracle preflight failed: ${(result.stdout || result.stderr).trim().slice(0, 1600)}`);
	const report = JSON.parse(result.stdout);
	if (report.status !== "verified") throw new Error("v2 oracle preflight did not verify the record");
	return report;
}

/** Writes `<answer>.score.json` and returns the readable summary the scorer prints. */
export async function scoreAnswer(answerPath: string, recordPath: string, options?: V2OracleOptions) {
	const reportPath = answerPath.replace(/\.json$/, ".score.json");
	if (protocol(recordPath) === 2) {
		if (protocol(answerPath) !== 2) throw new Error("v2 record requires a v2 answer");
		const receiptArgs = options?.runReceiptBundle ? ["--run-receipts", options.runReceiptBundle,
			"--trusted-run-receipts-sha256", options.trustedRunReceiptsSha256 ?? ""] : [];
		const result = python(SCORER_V2, ["score", "--record", recordPath, "--answer", answerPath, ...oracleArgs(options), ...receiptArgs]);
		if (result.status !== 0) throw new Error(`v2 scoring failed: ${(result.stdout || result.stderr).trim().slice(0, 1600)}`);
		const report = JSON.parse(result.stdout);
		if (report.status !== "scored") throw new Error(`v2 scoring returned ${report.status}`);
		const digest = (path: string) => createHash("sha256").update(readFileSync(path)).digest("hex");
		Object.assign(report, {
			answer_sha256: digest(answerPath), ground_truth_sha256: digest(recordPath),
			scorer_sha256: digest(SCORER_V2),
			answer_schema_sha256: digest(join(HARNESS, "schema", "contestant-answer-v2.schema.json")),
			oracle_bundle_sha256: options!.trustedOracleSha256,
			run_receipt_bundle_sha256: options?.trustedRunReceiptsSha256 ?? null,
		});
		await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`);
		return { report, text: JSON.stringify({ status: report.status, benchmark_eligible: report.benchmark_eligible,
			evaluation_revision: report.evaluation_revision, scoring_policy: report.scoring_policy,
			satisfies_required_actions: report.satisfies_required_actions, satisfies_any_repair: report.satisfies_any_repair,
			metrics: report.metrics, critical_downstream_action_misses: report.critical_downstream_action_misses }, null, 2) };
	}
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

export function aggregateV2(recordPath: string, options: V2OracleOptions, runsDir: string) {
	const result = python(AGGREGATOR_V2, ["--record", recordPath, ...oracleArgs(options), "--runs-dir", runsDir]);
	if (result.status !== 0) throw new Error(`v2 aggregation failed: ${(result.stdout || result.stderr).trim().slice(0, 1600)}`);
	return result.stdout.trimEnd();
}
