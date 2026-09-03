#!/usr/bin/env node
// The harness pipeline, one run at a time:
//   isolate the estate -> restrict the tools -> prompt the model -> extract the
//   answer -> stamp provenance -> validate -> score.
// A run that fails any stage is reported and skipped; the rest keep going.
// After every run, the corpus scorecard is printed.
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
	createAgentSession,
	DefaultResourceLoader,
	getAgentDir,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";
import { extractAnswer } from "./answer.ts";
import {
	assertDirectory,
	captureEstateSnapshot,
	createRestrictedReadOnlyTools,
	DEFAULT_ESTATE,
	isolate,
} from "./estate.ts";
import type { EstateSnapshot } from "./estate.ts";
import { RECORDS, RUN_DIR, RUNS } from "./paths.ts";
import { aggregate, scoreAnswer, validateFile } from "./scoring.ts";

const PI_ENTRY = fileURLToPath(import.meta.resolve("@earendil-works/pi-coding-agent"));
const PI_PACKAGE = resolve(dirname(PI_ENTRY), "..", "package.json");

// Harness policy. Identical for every contestant, so only provider/model varies.
export const FIXED_TOOLS = ["read", "grep", "find", "ls"] as const;
export const FIXED_THINKING = "off";

interface Contestant {
	label: string;
	description?: string;
	provider: string;
	model: string;
}

interface RunnerArgs {
	record: string[];
	contestant: string[];
	runs: number;
	estate: string;
	timeout: number;
}

interface RunContext {
	estate: EstateSnapshot;
	piVersion: string;
}

const readJson = async (path: string) => JSON.parse(await readFile(path, "utf8"));

function positiveInteger(raw: string, flag: string) {
	const value = Number(raw);
	if (!Number.isInteger(value) || value <= 0) {
		throw new Error(`${flag} must be a positive integer`);
	}
	return value;
}

export function parseArgs(argv: string[]): RunnerArgs {
	const args: RunnerArgs = {
		record: [],
		contestant: [],
		runs: 3,
		estate: DEFAULT_ESTATE,
		timeout: 1800,
	};
	for (let index = 0; index < argv.length; index++) {
		const flag = argv[index];
		if (!["--record", "--contestant", "--runs", "--estate", "--timeout"].includes(flag)) {
			throw new Error(`unknown flag ${flag}`);
		}
		const value = argv[++index];
		if (value === undefined || value.startsWith("--")) throw new Error(`missing value for ${flag}`);
		if (flag === "--record") args.record.push(value);
		else if (flag === "--contestant") args.contestant.push(value);
		else if (flag === "--runs") args.runs = positiveInteger(value, flag);
		else if (flag === "--estate") args.estate = value;
		else args.timeout = positiveInteger(value, flag);
	}
	if (args.record.length === 0) throw new Error("--record is required (change id, or 'all')");
	return args;
}

export function buildPrompt(record: any, template: string) {
	const change = record.proposed_change ?? {};
	return template
		.replaceAll("{{QUERY}}", record.query)
		.replaceAll("{{CHANGE_REPO}}", change.repo ?? "unspecified")
		.replaceAll("{{CHANGE_DIFF}}", change.diff ?? "(not supplied)")
		.replaceAll("{{CHANGE_ID}}", record.change_id);
}

function sha256(value: string | Buffer) {
	return createHash("sha256").update(value).digest("hex");
}

function stableValue(value: any): any {
	if (Array.isArray(value)) return value.map(stableValue);
	if (value && typeof value === "object") {
		return Object.fromEntries(Object.keys(value).sort().map((key) => [key, stableValue(value[key])]));
	}
	return value;
}

function stableHash(value: any) {
	return sha256(JSON.stringify(stableValue(value)));
}

function modelDigest(contestant: Contestant) {
	if (contestant.provider !== "ollama") return null;
	const result = spawnSync("ollama", ["list"], { encoding: "utf8" });
	if (result.status !== 0) return null;
	for (const line of result.stdout.split("\n").slice(1)) {
		const [name, digest] = line.trim().split(/\s+/);
		if (name === contestant.model) return digest || null;
	}
	return null;
}

export function withTimeout<T>(operation: Promise<T>, timeoutSeconds: number, onTimeout: () => void) {
	return new Promise<T>((resolvePromise, rejectPromise) => {
		let settled = false;
		const timer = setTimeout(() => {
			if (settled) return;
			settled = true;
			onTimeout();
			rejectPromise(new Error(`timed out after ${timeoutSeconds}s`));
		}, timeoutSeconds * 1000);
		operation.then(
			(value) => {
				if (settled) return;
				settled = true;
				clearTimeout(timer);
				resolvePromise(value);
			},
			(error) => {
				if (settled) return;
				settled = true;
				clearTimeout(timer);
				rejectPromise(error);
			},
		);
	});
}

/** Prompt the model inside a throwaway copy of the estate; return its raw output. */
async function ask(prompt: string, model: any, modelRuntime: ModelRuntime, estate: string, timeoutSeconds: number) {
	const scratch = await mkdtemp(join(tmpdir(), "poc-run-"));
	let session: Awaited<ReturnType<typeof createAgentSession>>["session"] | undefined;
	let unsubscribe: (() => void) | undefined;
	let raw = "";
	try {
		const workdir = await isolate(estate, scratch);
		({ session } = await createAgentSession({
			cwd: workdir,
			model,
			thinkingLevel: FIXED_THINKING,
			tools: [...FIXED_TOOLS],
			customTools: (await createRestrictedReadOnlyTools(workdir)) as any,
			modelRuntime,
			sessionManager: SessionManager.inMemory(workdir),
			settingsManager: SettingsManager.inMemory({}),
			// No extensions, skills, templates, themes or CLAUDE.md/AGENTS.md discovery:
			// a scored run must not inherit local PI configuration.
			resourceLoader: new DefaultResourceLoader({
				cwd: workdir,
				agentDir: getAgentDir(),
				noExtensions: true,
				noSkills: true,
				noPromptTemplates: true,
				noThemes: true,
				noContextFiles: true,
			}),
		}));
		unsubscribe = session.subscribe((event: any) => {
			if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta") {
				raw += event.assistantMessageEvent.delta;
			}
		});
		const started = performance.now();
		await withTimeout(session.prompt(prompt), timeoutSeconds, () => {
			void session?.abort().catch(() => undefined);
		});
		const elapsed = Math.round((performance.now() - started) / 100) / 10;
		return { raw, elapsed, stats: session.getSessionStats() };
	} finally {
		unsubscribe?.();
		session?.dispose();
		await rm(scratch, { recursive: true, force: true });
	}
}

/** Everything needed to reproduce or invalidate this measurement later. */
function stampProvenance(answer: any, options: {
	record: any;
	name: string;
	contestant: Contestant;
	index: number;
	prompt: string;
	elapsed: number;
	stats: any;
	context: RunContext;
}) {
	const { record, name, contestant, index, prompt, elapsed, stats, context } = options;
	return Object.assign(answer, {
		change_id: record.change_id,
		contestant: contestant.label,
		runner: name,
		run_index: index,
		elapsed_seconds: elapsed,
		tokens_consumed: stats?.tokens.total,
		cost_usd: stats?.cost,
		model: `${contestant.provider}/${contestant.model}`,
		model_digest: modelDigest(contestant),
		pi_version: context.piVersion,
		prompt_sha256: sha256(prompt),
		contestant_config_sha256: stableHash({ contestant, tools: FIXED_TOOLS, thinking: FIXED_THINKING }),
		estate_sha256: context.estate.sha256,
		estate_repositories: context.estate.repositories,
		estate_revisions: context.estate.revisions,
		findings: answer.findings ?? {},
	});
}

async function runOnce(
	record: any,
	name: string,
	contestant: Contestant,
	modelRuntime: ModelRuntime,
	estate: string,
	index: number,
	timeoutSeconds: number,
	context: RunContext,
) {
	const model = modelRuntime.getModel(contestant.provider, contestant.model);
	if (!model) throw new Error(`model not in pi registry: ${contestant.provider}/${contestant.model}`);
	const prompt = buildPrompt(record, await readFile(join(RUN_DIR, "prompt-template.md"), "utf8"));
	const { raw, elapsed, stats } = await ask(prompt, model, modelRuntime, estate, timeoutSeconds);

	let answer: any;
	try {
		answer = extractAnswer(raw);
	} catch (error) {
		await mkdir(RUNS, { recursive: true });
		const rawPath = join(RUNS, `${record.change_id}-${name}-${index}.raw.txt`);
		await writeFile(rawPath, raw);
		throw new Error(`${(error as Error).message}; raw output saved to ${rawPath}`);
	}
	return stampProvenance(answer, { record, name, contestant, index, prompt, elapsed, stats, context });
}

export async function main(argv = process.argv.slice(2)) {
	const args = parseArgs(argv);
	const contestants: Record<string, Contestant> = await readJson(join(RUN_DIR, "contestants.json"));
	const chosen = args.contestant.length > 0 ? args.contestant : Object.keys(contestants);
	const unknown = chosen.filter((name) => !(name in contestants));
	if (unknown.length > 0) throw new Error(`unknown contestant(s): ${unknown.join(", ")}`);

	const recordPaths = args.record.includes("all")
		? (await readdir(RECORDS)).filter((file) => file.endsWith(".json")).sort().map((file) => join(RECORDS, file))
		: args.record.map((id) => join(RECORDS, `${id}.json`));

	const estate = resolve(args.estate);
	await assertDirectory(estate, "estate");
	const context: RunContext = {
		estate: await captureEstateSnapshot(estate),
		piVersion: (await readJson(PI_PACKAGE)).version,
	};
	await mkdir(RUNS, { recursive: true });
	console.log(`estate ${context.estate.sha256.slice(0, 12)} (${context.estate.repositories.length} repositories)`);

	const modelRuntime = await ModelRuntime.create();
	let failures = 0;
	for (const recordPath of recordPaths) {
		validateFile("record", recordPath);
		const record = await readJson(recordPath);
		if (record.status !== "frozen") {
			console.log(`skipping ${record.change_id}: status is ${record.status ?? "unset"}, only frozen records are scored`);
			continue;
		}
		for (const name of chosen) {
			for (let index = 1; index <= args.runs; index++) {
				const stem = `${record.change_id}-${name}-${index}`;
				console.log(`\nrunning ${stem} ...`);
				try {
					const answer = await runOnce(
						record, name, contestants[name], modelRuntime, estate, index, args.timeout, context,
					);
					const answerPath = join(RUNS, `${stem}.json`);
					// Drop any score from a previous run of this stem before writing the new answer,
					// so a failure below cannot leave a stale score paired with a fresh answer.
					await rm(answerPath.replace(/\.json$/, ".score.json"), { force: true });
					await writeFile(answerPath, `${JSON.stringify(answer, null, 2)}\n`);
					validateFile("answer", answerPath);
					console.log((await scoreAnswer(answerPath, recordPath)).text);
				} catch (error) {
					failures++;
					console.log(`  FAILED: ${(error as Error).message}`);
				}
			}
		}
	}

	console.log(`\n--- scorecard across every scored run ---\n`);
	console.log(aggregate());
	if (failures > 0) {
		console.log(`\n${failures} run(s) failed and were not scored.`);
		process.exitCode = 1;
	}
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
	main().catch((error) => {
		console.error(`FAILED: ${(error as Error).message}`);
		process.exitCode = 1;
	});
}
