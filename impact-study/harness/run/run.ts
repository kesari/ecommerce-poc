#!/usr/bin/env node
// The harness pipeline, one run at a time:
//   isolate the estate -> restrict the tools -> prompt the model -> extract the
//   answer -> stamp provenance -> validate -> score.
// A run that fails any stage is reported and skipped; the rest keep going.
// After every run, the corpus scorecard is printed.
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile } from "node:fs/promises";
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
	countToolCalls,
	createRestrictedReadOnlyTools,
	DEFAULT_ESTATE,
	ESTATE_REPOSITORIES,
	isolate,
} from "./estate.ts";
import type { EstateSnapshot } from "./estate.ts";
import { buildConceptIndex, buildContractGraph, buildSymbolIndex, indexPromptSection } from "./indexes.ts";
import { RECORDS, RUN_DIR, RUNS } from "./paths.ts";
import { createRealProduct } from "./products.ts";
import type { ProductConfig, ProductReceipt } from "./products.ts";
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
	/** Precomputed index support: "symbol" (SCIP-like), "contract" (Gortex-like), "concept" (Graphify-like). */
	indexes?: ("symbol" | "contract" | "concept")[];
	product?: ProductConfig;
	enabled?: boolean;
}

interface RunnerArgs {
	record: string[];
	contestant: string[];
	runs: number;
	estate: string;
	timeout: number;
	minToolCalls: number;
	minTokens: number;
}

interface RunContext {
	estate: EstateSnapshot;
	piVersion: string;
	harnessSha256: string;
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
		minToolCalls: 10,
		minTokens: 8000,
	};
	for (let index = 0; index < argv.length; index++) {
		const flag = argv[index];
		if (!["--record", "--contestant", "--runs", "--estate", "--timeout", "--min-tool-calls", "--min-tokens"].includes(flag)) {
			throw new Error(`unknown flag ${flag}`);
		}
		const value = argv[++index];
		if (value === undefined || value.startsWith("--")) throw new Error(`missing value for ${flag}`);
		if (flag === "--record") args.record.push(value);
		else if (flag === "--contestant") args.contestant.push(value);
		else if (flag === "--runs") args.runs = positiveInteger(value, flag);
		else if (flag === "--estate") args.estate = value;
		else if (flag === "--min-tool-calls") args.minToolCalls = positiveInteger(value, flag);
		else if (flag === "--min-tokens") args.minTokens = positiveInteger(value, flag);
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

/** Runner-stamped product-use summary, computed from receipts — never from model claims. */
export function productSummary(receipts: any[] | undefined) {
	if (!receipts) return null;
	const succeeded = receipts.filter((receipt: any) => receipt.success).length;
	return { attempted: receipts.length, succeeded, failed: receipts.length - succeeded };
}

/** A product run counts as product-assisted only with at least one
 * successful product call. Zero-use runs are kept and scored but marked
 * ineligible, so they feed adoption-rate analysis without polluting
 * product-assisted comparisons. Non-product contestants get null (N/A). */
export function productEligible(contestant: Contestant, receipts: any[] | undefined) {
	if (!contestant.product) return null;
	return (receipts ?? []).some((receipt: any) => receipt.success);
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

async function harnessHash() {
	const files = [
		"answer.ts", "estate.ts", "indexes.ts", "products.ts", "run.ts", "scoring.ts",
		"contestants.json", "prompt-template.md", "../scoring/score.py",
		"../schema/contestant-answer.schema.json", "../schema/change-ground-truth.schema.json",
	];
	const hash = createHash("sha256");
	for (const file of files.sort()) {
		hash.update(file).update("\0").update(await readFile(join(RUN_DIR, file))).update("\0");
	}
	return hash.digest("hex");
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

export function withTimeout<T>(operation: Promise<T>, timeoutSeconds: number, onTimeout: () => void | Promise<void>) {
	return new Promise<T>((resolvePromise, rejectPromise) => {
		let settled = false;
		const timer = setTimeout(async () => {
			if (settled) return;
			settled = true;
			await onTimeout();
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
async function ask(
	prompt: string,
	model: any,
	modelRuntime: ModelRuntime,
	estate: string,
	timeoutSeconds: number,
	indexes: readonly ("symbol" | "contract" | "concept")[] = [],
	product?: ProductConfig,
	snapshot?: EstateSnapshot,
) {
	const scratch = await mkdtemp(join(tmpdir(), "poc-run-"));
	let session: Awaited<ReturnType<typeof createAgentSession>>["session"] | undefined;
	let unsubscribe: (() => void) | undefined;
	let raw = "";
	try {
		const workdir = await isolate(estate, scratch);
		// Deterministic indexes are built from the isolated copy, so an
		// index-backed contestant sees the same blind estate as the baseline.
		const indexShas: string[] = [];
		if (indexes.includes("symbol")) {
			indexShas.push((await buildSymbolIndex(workdir, ESTATE_REPOSITORIES)).sha256);
		}
		if (indexes.includes("contract")) {
			indexShas.push((await buildContractGraph(workdir, ESTATE_REPOSITORIES)).sha256);
		}
		if (indexes.includes("concept")) {
			indexShas.push((await buildConceptIndex(workdir, ESTATE_REPOSITORIES)).sha256);
		}
		let productReceipt: ProductReceipt | undefined;
		let productReceipts: any[] = [];
		let productPrompt = "";
		let productTools: any[] = [];
		if (product) {
			if (!snapshot) throw new Error("estate snapshot is required for a real product");
			const integration = await createRealProduct(product, estate, snapshot);
			productReceipt = integration.receipt;
			productReceipts = integration.receipts;
			productPrompt = integration.prompt;
			productTools = integration.tools;
		}
		const tools = [...(await createRestrictedReadOnlyTools(workdir)) as any[], ...productTools];
		const toolCalls = countToolCalls(tools);
		// Product tools must be named in the allowlist or PI filters them
		// out and the agent never sees them; a run that silently drops
		// them scores agent/file-search work as product evidence.
		const allowedTools = [...FIXED_TOOLS, ...productTools.map((tool: any) => tool.name)];
		({ session } = await createAgentSession({
			cwd: workdir,
			model,
			thinkingLevel: FIXED_THINKING,
			tools: allowedTools,
			customTools: tools as any,
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
		const effectivePrompt = productPrompt ? `${prompt}\n\n${productPrompt}` : prompt;
		await withTimeout(session.prompt(effectivePrompt), timeoutSeconds, async () => {
			await session?.abort().catch(() => undefined);
		});
		const elapsed = Math.round((performance.now() - started) / 100) / 10;
		return { raw, elapsed, stats: session.getSessionStats(), toolCalls: toolCalls(), toolTelemetry: (toolCalls as any).telemetry(), indexSha: productReceipt?.artifact_sha256 ?? (indexShas.length > 0 ? sha256(indexShas.join("\0")) : "none"), productReceipt, productReceipts, effectivePrompt, allowedTools };
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
	runStartedAt: string;
	toolCalls?: number;
	toolTelemetry?: any;
	indexSha?: string;
	productReceipt?: ProductReceipt;
	productReceipts?: any[];
	allowedTools?: string[];
}) {
	const { record, name, contestant, index, prompt, elapsed, stats, context, runStartedAt, toolCalls, toolTelemetry, indexSha, productReceipt, productReceipts, allowedTools } = options;
	// Manual rubric scores must come from an out-of-band rubric file, never
	// from the model. Drop any model-supplied values so they cannot grade
	// their own composite or suppress weight renormalization.
	delete answer.freshness_score;
	delete answer.operational_cost_score;
	return Object.assign(answer, {
		change_id: record.change_id,
		contestant: contestant.label,
		runner: name,
		run_index: index,
		run_started_at: runStartedAt,
		elapsed_seconds: elapsed,
		tool_calls: toolCalls ?? null,
		tool_calls_by_name: toolTelemetry?.byName ?? null,
		product_tool_calls: productSummary(productReceipts),
		product_tool_receipts: productReceipts ?? null,
		product_assisted_eligible: productEligible(contestant, productReceipts),
		index_sha256: indexSha ?? "none",
		product_provenance: productReceipt ?? null,
		tokens_consumed: stats?.tokens.total,
		cost_usd: stats?.cost,
		model: `${contestant.provider}/${contestant.model}`,
		model_digest: modelDigest(contestant),
		pi_version: context.piVersion,
		prompt_sha256: sha256(prompt),
		contestant_config_sha256: stableHash({ contestant, tools: allowedTools ?? [...FIXED_TOOLS], thinking: FIXED_THINKING }),
		estate_sha256: context.estate.sha256,
		harness_sha256: context.harnessSha256,
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
	const template = await readFile(join(RUN_DIR, "prompt-template.md"), "utf8");
	const section = indexPromptSection(contestant.indexes ?? []);
	const prompt = buildPrompt(record, template) + (section ? `\n\n${section}` : "");
	const runStartedAt = new Date().toISOString();
	const { raw, elapsed, stats, toolCalls, toolTelemetry, indexSha, productReceipt, productReceipts, effectivePrompt, allowedTools } = await ask(
		prompt, model, modelRuntime, estate, timeoutSeconds, contestant.indexes ?? [], contestant.product, context.estate,
	);

	let answer: any;
	try {
		answer = extractAnswer(raw);
	} catch (error) {
		await mkdir(RUNS, { recursive: true });
		const stamp = runStartedAt.replace(/[:.]/g, "-");
		const rawPath = join(RUNS, `${record.change_id}-${name}-${index}.${stamp}.raw.txt`);
		await writeFile(rawPath, raw);
		throw new Error(`${(error as Error).message}; raw output saved to ${rawPath}`);
	}
	return stampProvenance(answer, { record, name, contestant, index, prompt: effectivePrompt, elapsed, stats, context, runStartedAt, toolCalls, toolTelemetry, indexSha, productReceipt, productReceipts, allowedTools });
}

export async function main(argv = process.argv.slice(2)) {
	const args = parseArgs(argv);
	const contestants: Record<string, Contestant> = await readJson(join(RUN_DIR, "contestants.json"));
	const chosen = args.contestant.length > 0 ? args.contestant : Object.keys(contestants).filter((name) => contestants[name].enabled !== false);
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
		harnessSha256: await harnessHash(),
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
					if ((answer.tool_calls ?? 0) < args.minToolCalls || (answer.tokens_consumed ?? 0) < args.minTokens) {
						failures++;
						const reason = `thin run: ${answer.tool_calls ?? 0} tool calls or ${answer.tokens_consumed ?? 0} tokens below minimums ${args.minToolCalls}/${args.minTokens}; answer kept for diagnosis but not scored`;
						console.log(`  FAILED: ${reason}`);
						const stamp = (answer.run_started_at ?? new Date().toISOString()).replace(/[:.]/g, "-");
						await writeFile(join(RUNS, `${stem}.${stamp}.json`), `${JSON.stringify(answer, null, 2)}\n`);
						let thinStub = join(RUNS, `${stem}.rejected.json`);
						try {
							await stat(thinStub);
							thinStub = join(RUNS, `${stem}.${stamp}.rejected.json`);
						} catch {
							// No previous stub: use the canonical name.
						}
						await writeFile(thinStub, `${JSON.stringify({
							change_id: record.change_id,
							runner: name,
							run_index: index,
							run_started_at: answer.run_started_at ?? new Date().toISOString(),
							tool_calls: answer.tool_calls ?? 0,
							reason,
							estate_sha256: context.estate.sha256,
						}, null, 2)}\n`);
						continue;
					}
					let answerPath = join(RUNS, `${stem}.json`);
					// Never overwrite a previous measurement undetectably. If the
					// stem already exists, suffix with the run timestamp instead.
					try {
						await stat(answerPath);
						const stamp = (answer.run_started_at ?? new Date().toISOString()).replace(/[:.]/g, "-");
						answerPath = join(RUNS, `${stem}.${stamp}.json`);
					} catch {
						// No previous file: use the canonical stem.
					}
					// Drop any score from a previous run of this stem before writing the new answer,
					// so a failure below cannot leave a stale score paired with a fresh answer.
					await rm(answerPath.replace(/\.json$/, ".score.json"), { force: true });
					await writeFile(answerPath, `${JSON.stringify(answer, null, 2)}\n`);
					validateFile("answer", answerPath);
					console.log((await scoreAnswer(answerPath, recordPath)).text);
				} catch (error) {
					failures++;
					const reason = (error as Error).message;
					console.log(`  FAILED: ${reason}`);
					try {
						const stub = {
							change_id: record.change_id,
							runner: name,
							run_index: index,
							run_started_at: new Date().toISOString(),
							reason,
							estate_sha256: context.estate.sha256,
						};
						let stubPath = join(RUNS, `${stem}.rejected.json`);
						try {
							await stat(stubPath);
							const stamp = (stub.run_started_at as string).replace(/[:.]/g, "-");
							stubPath = join(RUNS, `${stem}.${stamp}.rejected.json`);
						} catch {
							// No previous stub: use the canonical name.
						}
						await writeFile(stubPath, `${JSON.stringify(stub, null, 2)}\n`);
					} catch {
						// Rejection stub is best-effort; the failure above is what matters.
					}
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
