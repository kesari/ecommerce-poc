#!/usr/bin/env node
// The harness pipeline, one run at a time:
//   isolate the estate -> restrict the tools -> prompt the model -> extract the
//   answer -> stamp provenance -> validate -> score.
// A run that fails any stage is reported and skipped; the rest keep going.
// After every run, the corpus scorecard is printed.
import { createHash, randomUUID } from "node:crypto";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, readdir, realpath, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import {
	createAgentSession,
	DefaultResourceLoader,
	getAgentDir,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";
import { AttemptBudget, AttemptError, superviseAttempt } from "./budget.ts";
import type { BudgetPolicy } from "./budget.ts";
import { extractAnswer } from "./answer.ts";
import { claimCampaignSlot, loadFrozenCampaign, reserveCampaignBudget, settleCampaignBudget, preflightCampaignSlot, SAFE_CASE } from "./campaign.ts";
import type { CampaignBudgetReservation, FrozenCampaign, MeasurementIdentity } from "./campaign.ts";
import {
	assertDirectory,
	assertEstateSnapshot,
	captureEstateSnapshot,
	countToolCalls,
	createRestrictedReadOnlyTools,
	DEFAULT_ESTATE,
	ESTATE_REPOSITORIES,
	isolate,
} from "./estate.ts";
import type { EstateSnapshot } from "./estate.ts";
import { buildConceptIndex, buildContractGraph, buildSymbolIndex, indexPromptSection } from "./indexes.ts";
import { candidateSection, deriveAnchor, playbookFor, runPlaybook } from "./playbook.ts";
import type { ChangeAnchor } from "./playbook.ts";
import { HARNESS, RECORDS, RUN_DIR, RUNS } from "./paths.ts";
import { createRealProduct } from "./products.ts";
import type { ProductConfig, ProductReceipt } from "./products.ts";
import { aggregate, aggregateV2, preflightV2, scoreAnswer, validateFile } from "./scoring.ts";

const PI_ENTRY = fileURLToPath(import.meta.resolve("@earendil-works/pi-coding-agent"));
const PI_PACKAGE = resolve(dirname(PI_ENTRY), "..", "package.json");

// Harness policy. Identical for every contestant, so only provider/model varies.
export const FIXED_TOOLS = ["read", "grep", "find", "ls"] as const;
export const FIXED_THINKING = "off";
// Track C phase B: extra product calls the agent may make beyond the frozen
// playbook. The playbook is the guaranteed evidence; this is headroom to
// follow a thread, not a second bite at retrieval.
export const PHASE_B_PRODUCT_BUDGET = 6;

interface Contestant {
	label: string;
	description?: string;
	provider: string;
	model: string;
	/** Precomputed index support: "symbol" (SCIP-like), "contract" (Gortex-like), "concept" (Graphify-like). */
	indexes?: ("symbol" | "contract" | "concept")[];
	product?: ProductConfig;
	/** Track C: run the frozen playbook before the model, then verify. */
	product_first?: boolean;
	enabled?: boolean;
}

interface RunnerArgs {
	record: string[];
	contestant: string[];
	runs: number;
	estate: string;
	timeout: number;
	protocol: 1 | 2;
	oracleBundle?: string;
	trustedOracleSha256?: string;
	artifactRoot?: string;
	maxTokens?: number;
	maxCostUsd?: number;
	runGroup?: string;
	campaignSchedule?: string;
	campaignCasesManifest?: string;
	trustedCampaignScheduleSha256?: string;
	campaignConditions?: string;
	trustedCampaignConditionsSha256?: string;
	campaignRepeat?: number;
}

interface RunContext {
	estate: EstateSnapshot;
	piVersion: string;
	harnessSha256: string;
	outputDir: string;
	budgetPolicy: BudgetPolicy;
	campaign?: FrozenCampaign;
	campaignClaimDir?: string;
	campaignRuntime?: { scorerSha256: string; answerSchemaSha256: string; taskTemplateSha256: string; scoringPolicy: string; recordSha256?: string; oracleSha256?: string; treatment: string };
	oracleOptions?: { oracleBundle: string; trustedOracleSha256: string; artifactRoot: string };
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
		protocol: 1,
	};
	for (let index = 0; index < argv.length; index++) {
		const flag = argv[index];
		if (!["--record", "--contestant", "--runs", "--estate", "--timeout", "--protocol", "--oracle-bundle", "--trusted-oracle-sha256", "--artifact-root", "--max-tokens", "--max-cost-usd", "--run-group", "--campaign-schedule", "--campaign-cases-manifest", "--trusted-campaign-schedule-sha256", "--campaign-conditions", "--trusted-campaign-conditions-sha256", "--campaign-repeat"].includes(flag)) {
			throw new Error(`unknown flag ${flag}`);
		}
		const value = argv[++index];
		if (value === undefined || value.startsWith("--")) throw new Error(`missing value for ${flag}`);
		if (flag === "--record") args.record.push(value);
		else if (flag === "--contestant") args.contestant.push(value);
		else if (flag === "--runs") args.runs = positiveInteger(value, flag);
		else if (flag === "--estate") args.estate = value;
		else if (flag === "--protocol") {
			if (value !== "1" && value !== "2") throw new Error("--protocol must be 1 or 2");
			args.protocol = Number(value) as 1 | 2;
		}
		else if (flag === "--oracle-bundle") args.oracleBundle = value;
		else if (flag === "--trusted-oracle-sha256") args.trustedOracleSha256 = value;
		else if (flag === "--artifact-root") args.artifactRoot = value;
		else if (flag === "--run-group") {
			if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$/.test(value)) throw new Error("--run-group must be a directory-safe label of at most 64 characters");
			args.runGroup = value;
		}
		else if (flag === "--campaign-schedule") args.campaignSchedule = value;
		else if (flag === "--campaign-cases-manifest") args.campaignCasesManifest = value;
		else if (flag === "--campaign-conditions") args.campaignConditions = value;
		else if (flag === "--trusted-campaign-schedule-sha256") args.trustedCampaignScheduleSha256 = value;
		else if (flag === "--trusted-campaign-conditions-sha256") args.trustedCampaignConditionsSha256 = value;
		else if (flag === "--campaign-repeat") args.campaignRepeat = positiveInteger(value, flag);
		else if (flag === "--max-tokens") args.maxTokens = positiveInteger(value, flag);
		else if (flag === "--max-cost-usd") {
			args.maxCostUsd = Number(value);
			if (!Number.isFinite(args.maxCostUsd) || args.maxCostUsd <= 0) throw new Error("--max-cost-usd must be finite and positive");
		}
		else args.timeout = positiveInteger(value, flag);
	}
	if (args.record.length === 0) throw new Error("--record is required (change id, or 'all')");
	if (args.record.some((id) => id !== "all" && !SAFE_CASE.test(id))) {
		throw new Error("--record must be a change id or 'all'");
	}
	if (args.protocol === 2) {
		if (!args.maxTokens || !args.maxCostUsd || !argv.includes("--timeout")) throw new Error("protocol 2 model runs require explicit --max-tokens, --max-cost-usd, and --timeout ceilings");
		if (!args.oracleBundle || !args.trustedOracleSha256 || !args.artifactRoot) {
			throw new Error("protocol 2 requires --oracle-bundle, --trusted-oracle-sha256, and --artifact-root before any model run");
		}
		if (!/^[a-f0-9]{64}$/.test(args.trustedOracleSha256)) throw new Error("--trusted-oracle-sha256 must be a SHA-256 digest");
	} else if (args.oracleBundle || args.trustedOracleSha256 || args.artifactRoot || args.runGroup) {
		throw new Error("oracle options and --run-group require --protocol 2");
	}
	const campaignValues = [args.campaignSchedule, args.campaignCasesManifest, args.campaignConditions, args.trustedCampaignScheduleSha256, args.trustedCampaignConditionsSha256, args.campaignRepeat];
	if (campaignValues.some(value => value !== undefined)) {
		if (args.protocol !== 2 || campaignValues.some(value => value === undefined)) throw new Error("campaign execution requires protocol 2 and all six frozen campaign options");
		if (!/^[a-f0-9]{64}$/.test(args.trustedCampaignScheduleSha256!) || !/^[a-f0-9]{64}$/.test(args.trustedCampaignConditionsSha256!)) throw new Error("campaign trusted digests must be SHA-256");
		if (args.campaignRepeat! > 3 || args.record.length !== 1 || args.record[0] === "all" || args.contestant.length !== 1 || (argv.includes("--runs") && args.runs !== 1)) throw new Error("campaign invocation selects exactly one case, arm, and repetition (1..3)");
		args.runs = 1;
	}
	return args;
}

export function buildPrompt(record: any, template: string) {
	const change = record.proposed_change ?? {};
	return template
		.replaceAll("{{QUERY}}", record.query)
		.replaceAll("{{REPOSITORY_UNIVERSE}}", record.repository_universe?.length ? [...record.repository_universe].sort().join(", ") : "Use the repository names available in source.")
		.replaceAll("{{CHANGE_REPO}}", change.repo ?? "unspecified")
		.replaceAll("{{CHANGE_DIFF}}", change.diff ?? "(not supplied)")
		.replaceAll("{{CHANGE_ID}}", record.change_id)
		.replaceAll("{{SNAPSHOT_SHA256}}", record.snapshot_sha256 ?? "");
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

/** Natural-adoption runs need one successful call. Product-first runs must
 * reach the real adapter for every scheduled step, persist every attempt, and
 * get at least one answer. Zero-use or bypassed runs remain available for
 * diagnostics without polluting product-assisted comparisons. */
export function productEligible(contestant: Contestant, receipts: any[] | undefined, playbook?: any[]) {
	if (!contestant.product) return null;
	const recorded = receipts ?? [];
	if (!recorded.some((receipt: any) => receipt.success === true)) return false;
	if (!contestant.product_first) return true;
	if (!playbook?.length) return false;
	const ids = new Set(recorded.map((receipt: any) => receipt.id));
	if (ids.size !== recorded.length || recorded.some((receipt: any) => !receipt.id)) return false;
	const used = new Set<string>();
	return playbook.every((entry: any) => {
		const receipt = recorded.find((candidate: any) => candidate.id === entry.receipt_id);
		if (!receipt || used.has(receipt.id) || receipt.tool !== entry.step.tool || receipt.success !== entry.ok) return false;
		used.add(receipt.id);
		return Object.entries(entry.step.params).every(([key, value]) =>
			JSON.stringify(stableValue(receipt.parameters?.[key])) === JSON.stringify(stableValue(value)));
	});
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

export async function harnessHash() {
	const files = [
		"answer.ts", "budget.ts", "campaign.ts", "estate.ts", "freeze_campaign.ts", "indexes.ts", "paths.ts", "playbook.ts", "products.ts", "run.ts", "scoring.ts",
		"aggregate.py", "aggregate_v2.py", "aggregate_paired.py", "package.json", "package-lock.json",
		"contestants.json", "prompt-template.md", "prompt-template-v2.md", "../scoring/score.py", "../scoring/score_v2.py",
		"../schema/contestant-answer.schema.json", "../schema/change-ground-truth.schema.json",
		"../schema/contestant-answer-v2.schema.json", "../schema/change-ground-truth-v2.schema.json", "../schema/oracle-bundle-v2.schema.json",
		"../schema/run-receipts-v2.schema.json",
	];
	const hash = createHash("sha256");
	for (const file of files.sort()) {
		hash.update(file).update("\0").update(await readFile(join(RUN_DIR, file))).update("\0");
	}
	return hash.digest("hex");
}

/** The v2 verifier identifies source snapshots by pinned commit and tree, not
 * the runner's separate content fingerprint (which includes working files). */
export function verifyPinnedSnapshot(estate: string, snapshot: EstateSnapshot, pins: any) {
	const repositories = Object.keys(pins.repositories ?? {}).sort();
	if (JSON.stringify([...snapshot.repositories].sort()) !== JSON.stringify(repositories)) {
		throw new Error("v2 snapshot repository set differs from pins");
	}
	const verified: Record<string, { commit: string; tree: string }> = {};
	for (const repo of repositories) {
		const pin = pins.repositories[repo];
		const revision = snapshot.revisions.find((entry) => entry.name === repo);
		const identity = spawnSync("git", ["-C", join(estate, repo), "rev-parse", "HEAD", "HEAD^{tree}"], { encoding: "utf8" });
		const status = spawnSync("git", ["-C", join(estate, repo), "status", "--porcelain", "--untracked-files=all", "--", "."], { encoding: "utf8" });
		const [commit, tree] = identity.stdout.trim().split("\n");
		if (!revision || revision.commit !== pin.commit || revision.dirty !== false || identity.status !== 0
			|| commit !== pin.commit || tree !== pin.tree || status.status !== 0 || status.stdout.trim() !== "") {
			throw new Error(`v2 snapshot drift at ${repo}: expected clean pinned commit and tree`);
		}
		verified[repo] = { commit: pin.commit, tree: pin.tree };
	}
	return stableHash(verified);
}

export async function assertVerifierOutsideEstate(estate: string, paths: string[], repositories: readonly string[] = ESTATE_REPOSITORIES) {
	const roots = await Promise.all(repositories.map((repo) => realpath(join(estate, repo))));
	for (const path of paths) {
		const target = await realpath(path);
		for (const root of roots) {
			const fromRoot = relative(root, target);
			if (fromRoot === "" || (!isAbsolute(fromRoot) && fromRoot !== ".." && !fromRoot.startsWith(`..${sep}`))) {
				throw new Error(`v2 oracle artifact must be outside the searchable estate: ${path}`);
			}
		}
	}
}

export function declaredTools(contestant: Contestant): string[] {
	const productTools: Record<string, string[]> = { scip: ["scip_search"], graphify: ["graphify_query"],
		gortex: ["gortex_query", "gortex_contracts"], repowise: ["repowise_search", "repowise_context", "repowise_blast_radius"],
		"codebase-memory": ["codebase_memory_query"] };
	if (contestant.product && !productTools[contestant.product.kind]) throw new Error("unsupported product tool identity");
	return [...FIXED_TOOLS, ...(contestant.product ? productTools[contestant.product.kind] : [])];
}
export function contestantConfigSha256(contestant: Contestant, budgetPolicy: BudgetPolicy) {
	return stableHash({ contestant, tools: declaredTools(contestant), thinking: FIXED_THINKING, budget_policy: budgetPolicy });
}
export function createMeasurementIdentity(name: string, contestant: Contestant, index: number, context: RunContext, taskPrompt: string, indexSha = "none"): MeasurementIdentity {
	return { runner: name, contestant: contestant.label, run_index: index, model: `${contestant.provider}/${contestant.model}`,
		model_digest: modelDigest(contestant), pi_version: context.piVersion, harness_sha256: context.harnessSha256,
		contestant_config_sha256: contestantConfigSha256(contestant, context.budgetPolicy), index_sha256: indexSha,
		thinking: FIXED_THINKING, estate_sha256: context.estate.sha256, task_prompt_sha256: sha256(taskPrompt) };
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

/** Runs inside a supervised process so synchronous native adapters cannot
 * block the parent's total deadline. Every paid request crosses reserve(). */
async function ask(payload: any, budget: AttemptBudget, signal: AbortSignal, emit: (value: any, durable?: boolean) => void | Promise<void>) {
	const { prompt, contestant, estate, snapshot, anchor, question, scratch } = payload;
	let session: Awaited<ReturnType<typeof createAgentSession>>["session"] | undefined;
	let unsubscribe: (() => void) | undefined;
	let raw = "";
	let productReceipt: ProductReceipt | undefined;
	let productReceipts: any[] = [];
	let playbook: any[] | undefined;
	let effectivePrompt = prompt;
	let toolCalls: ReturnType<typeof countToolCalls> | undefined;
	const indexShas: string[] = [];
	let observedIndexSha: string | null = null;
	const progress = (extra: any = {}, durable = false) => emit({ index_observed_sha256: observedIndexSha ?? productReceipt?.artifact_sha256 ?? null, productReceipt, productReceipts, playbook, effectivePrompt,
		stats: budget.stats, reservations: budget.reservations, toolCalls: toolCalls?.() ?? 0,
		toolTelemetry: toolCalls?.telemetry(), exposure: budget.exposure(), ...extra }, durable);
	try {
		budget.check();
		await mkdir(scratch);
		progress({ stage: "setup" });
		budget.check();
		if (snapshot) await assertEstateSnapshot(estate, snapshot);
		budget.check();
		const workdir = await isolate(estate, scratch);
		budget.check();
		if (snapshot) await assertEstateSnapshot(workdir, snapshot, true);
		for (const kind of contestant.indexes ?? []) {
			budget.check();
			if (kind === "symbol") indexShas.push((await buildSymbolIndex(workdir, ESTATE_REPOSITORIES)).sha256);
			if (kind === "contract") indexShas.push((await buildContractGraph(workdir, ESTATE_REPOSITORIES)).sha256);
			if (kind === "concept") indexShas.push((await buildConceptIndex(workdir, ESTATE_REPOSITORIES)).sha256);
		}
		let productPrompt = "";
		let productTools: any[] = [];
		if (contestant.product) {
			budget.check();
			if (!snapshot) throw new Error("estate snapshot is required for a real product");
			const integration = await createRealProduct(contestant.product, estate, snapshot);
			productReceipt = integration.receipt;
			productReceipts = integration.receipts;
			productPrompt = integration.prompt;
			productTools = integration.tools;
			for (const tool of productTools) {
				const execute = tool.execute.bind(tool);
				tool.execute = async (...args: any[]) => {
					budget.check();
					await assertEstateSnapshot(estate, snapshot);
					budget.check();
					try { return await execute(...args); }
					finally {
						// Preserve native receipt facts even when the following check fails.
						progress({ stage: "product" });
						budget.check();
						await assertEstateSnapshot(estate, snapshot);
					}
				};
			}
			if (anchor) {
				budget.check();
				if (!anchor.usable) throw new Error(`no usable change anchor for a product-first run: ${anchor.repo}`);
				playbook = await runPlaybook(productTools, playbookFor(contestant.product.kind, anchor, question ?? ""), productReceipts);
				budget.check();
				productPrompt = `${candidateSection(anchor, productReceipts)}\n\n${productPrompt}`;
				const spent = productReceipts.length;
				productTools = productTools.map((tool: any) => ({ ...tool, execute: async (id: string, params: any) => {
					budget.check();
					if (productReceipts.length - spent >= PHASE_B_PRODUCT_BUDGET) throw new Error(`product query budget spent (${PHASE_B_PRODUCT_BUDGET} beyond the playbook)`);
					return tool.execute(id, params);
				} }));
			}
		}
		budget.check();
		const tools = [...await createRestrictedReadOnlyTools(workdir) as any[], ...productTools];
		for (const tool of tools) {
			const execute = tool.execute.bind(tool);
			tool.execute = async (...args: any[]) => { budget.check(); return execute(...args); };
		}
		toolCalls = countToolCalls(tools);
		const allowedTools = [...FIXED_TOOLS, ...productTools.map((tool: any) => tool.name)];
		const indexSha = productReceipt?.artifact_sha256 ?? (indexShas.length ? sha256(indexShas.join("\0")) : "none");
		observedIndexSha = indexSha;
		if (payload.campaign) {
			if (JSON.stringify(allowedTools) !== JSON.stringify(payload.declaredTools)) throw new Error("campaign preflight: actual tool configuration differs from frozen declaration");
			preflightCampaignSlot(payload.campaign, { ...payload.measurementIdentity, index_sha256: indexSha }, payload.campaignRuntime);
		}
		const modelRuntime = await ModelRuntime.create({ signal, allowModelNetwork: false, refreshOnCreate: false, ...(payload.protocol === 2 ? { modelsPath: null } : {}) });
		const model = modelRuntime.getModel(contestant.provider, contestant.model);
		if (!model) throw new AttemptError("setup_failed", `model not in pi registry: ${contestant.provider}/${contestant.model}`);
		if (payload.campaign && `${model.provider}/${model.id}` !== payload.measurementIdentity.model) throw new Error("campaign preflight: registry model identity differs from frozen condition");
		const stream = modelRuntime.streamSimple.bind(modelRuntime);
		// SDK streamFn is async and awaits this result; ACK the reservation
		// in the parent ledger before any HTTP request can begin.
		(modelRuntime as any).streamSimple = async (model: any, context: any, options: any) => {
			let limits;
			try { limits = budget.reserve(model); }
			catch (error: any) { budget.stop(error); throw error; }
			await progress({ stage: "provider_request" }, true);
			budget.check();
			return stream(model, context, { ...options, ...limits, maxRetries: 0, ...(model.api === "openai-codex-responses" ? { transport: "sse" } : {}) });
		};
		({ session } = await createAgentSession({
			cwd: workdir, model, thinkingLevel: FIXED_THINKING, tools: allowedTools, customTools: tools as any,
			modelRuntime, sessionManager: SessionManager.inMemory(workdir),
			settingsManager: SettingsManager.inMemory({ retry: { enabled: false, provider: { maxRetries: 0 } }, compaction: { enabled: false } }),
			resourceLoader: new DefaultResourceLoader({ cwd: workdir, agentDir: getAgentDir(),
				noExtensions: true, noSkills: true, noPromptTemplates: true, noThemes: true, noContextFiles: true }),
		}));
		signal.addEventListener("abort", () => { void session?.abort().catch(() => undefined); }, { once: true });
		unsubscribe = session.subscribe((event: any) => {
			if (event.type === "message_update" && event.assistantMessageEvent.type === "text_delta") {
				raw += event.assistantMessageEvent.delta;
				emit({ raw_delta: event.assistantMessageEvent.delta });
			}
			budget.observe(event);
			if (event.type === "message_end") progress({ stage: "model" });
			if (budget.failure) void session?.abort().catch(() => undefined);
		});
		effectivePrompt = productPrompt ? `${prompt}\n\n${productPrompt}` : prompt;
		progress({ stage: "model" });
		budget.check();
		await session.prompt(effectivePrompt);
		budget.check();
		if (snapshot) { await assertEstateSnapshot(estate, snapshot); await assertEstateSnapshot(workdir, snapshot, true); }
		budget.check();
		return { raw, elapsed: (performance.now() - budget.started) / 1000, playbook, stats: budget.stats,
			toolCalls: toolCalls(), toolTelemetry: toolCalls.telemetry(), reservations: budget.reservations,
			indexSha: productReceipt?.artifact_sha256 ?? (indexShas.length ? sha256(indexShas.join("\0")) : "none"),
			productReceipt, productReceipts, effectivePrompt, allowedTools };
	} catch (error) {
		progress({ stage: budget.failure ? "budget_or_provider_failure" : "setup_or_execution_failure" });
		throw budget.failure ?? error;
	} finally {
		unsubscribe?.();
		session?.dispose();
		await rm(scratch, { recursive: true, force: true });
	}
}

async function attemptWorker() {
	if (!process.send) throw new Error("attempt worker requires a runner IPC channel");
	const controller = new AbortController();
	let budget: AttemptBudget | undefined;
	const acknowledgements = new Map<number, () => void>();
	let sequence = 0;
	process.on("message", async (message: any) => {
		if (message.type === "reservation_ack") { acknowledgements.get(message.id)?.(); acknowledgements.delete(message.id); return; }
		if (message.type === "abort") {
			budget?.stop(new AttemptError("budget_exhausted", "total attempt deadline exhausted"));
			controller.abort();
			return;
		}
		if (message.type !== "start" || budget) return;
		budget = new AttemptBudget(message.payload.policy, message.payload.deadlineEpochMs);
		try {
			const value = await ask(message.payload, budget, controller.signal,
				(value, durable) => {
					if (!durable) { process.send?.({ type: "progress", value }); return; }
					return new Promise<void>((resolve) => {
						const id = ++sequence; acknowledgements.set(id, resolve);
						process.send?.({ type: "reservation", id, value });
					});
				});
			process.send?.({ type: "result", value });
		} catch (error: any) {
			process.send?.({ type: "failure", status: error.status ?? "setup_failed", reason: error.message, evidence: error.evidence });
		}
	});
}

function stampedPlaybook(playbook: any[] | undefined) {
	return playbook ? playbook.map((entry: any) => ({
		intention: entry.step.intention, tool: entry.step.tool,
		operation: entry.step.params.operation ?? entry.step.params.action ?? null,
		parameters: entry.step.params, receipt_id: entry.receipt_id ?? null,
		ok: entry.ok, error: entry.error ?? null,
	})) : null;
}

/** Persist trusted runtime output before parsing any model-authored JSON. */
export async function persistRunReceipts(outputDir: string, record: any, name: string, runStartedAt: string, runtime: {
	prompt: string; estateSha256: string; productReceipt?: any; productReceipts?: any[]; playbook?: any[];
	budgetPolicy: BudgetPolicy; usage: { tokens_consumed: number; cost_usd: number; elapsed_seconds: number }; measurementIdentity?: MeasurementIdentity;
}) {
	if (!SAFE_CASE.test(record.change_id) || !/^[a-z0-9-]+$/i.test(name)) throw new Error("unsafe run identity");
	const directory = join(outputDir, "receipts");
	await mkdir(directory, { recursive: true });
	const path = join(directory, `${record.change_id}-${name}-${runStartedAt.replace(/[:.]/g, "-")}.json`);
	const envelope = {
		protocol_version: 2, change_id: record.change_id, snapshot_sha256: record.snapshot_sha256,
		run_started_at: runStartedAt, prompt_sha256: sha256(runtime.prompt), estate_sha256: runtime.estateSha256,
		receipts: runtime.productReceipts ?? [], product_provenance: runtime.productReceipt ?? null,
		product_playbook: stampedPlaybook(runtime.playbook), budget_policy: runtime.budgetPolicy, usage: runtime.usage,
		...(runtime.measurementIdentity ? { measurement_identity: runtime.measurementIdentity } : {}),
	};
	const bytes = `${JSON.stringify(envelope, null, 2)}\n`;
	await writeFile(path, bytes, { flag: "wx" });
	return { path, sha256: sha256(bytes) };
}

/** Everything needed to reproduce or invalidate this measurement later. */
export function stampProvenance(answer: any, options: {
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
	runReceiptBundle?: { path: string; sha256: string };
	playbook?: any[];
	reservations?: any[];
	attemptEvidence?: { path: string; sha256: string };
	measurementIdentity?: MeasurementIdentity;
}) {
	const { record, name, contestant, index, prompt, elapsed, stats, context, runStartedAt, toolCalls, toolTelemetry, indexSha, productReceipt, productReceipts, allowedTools } = options;
	// Manual rubric scores must come from an out-of-band rubric file, never
	// from the model. Drop any model-supplied values so they cannot grade
	// their own composite or suppress weight renormalization.
	delete answer.freshness_score;
	delete answer.operational_cost_score;
	delete answer.campaign_slot;
	delete answer.measurement_identity;
	delete answer.task_prompt_sha256;
	const { campaign_slot: _slot, ...identityFields } = options.measurementIdentity ?? {};
	if (record.protocol_version === 2) {
		answer.protocol_version = 2;
		answer.snapshot_sha256 = record.snapshot_sha256;
	} else {
		delete answer.protocol_version;
		delete answer.snapshot_sha256;
	}
	return Object.assign(answer, {
		change_id: record.change_id,
		contestant: contestant.label,
		runner: name,
		run_index: index,
		run_started_at: runStartedAt,
		elapsed_seconds: elapsed,
		budget_policy: context.budgetPolicy,
		budget_reservations: options.reservations ?? [],
		attempt_evidence: options.attemptEvidence ?? null,
		tool_calls: toolCalls ?? null,
		tool_calls_by_name: toolTelemetry?.byName ?? null,
		product_tool_calls: productSummary(productReceipts),
		// Present only on a Track C run: which frozen playbook steps ran, so a
		// product-first answer is never mistaken for a natural-adoption one.
		product_playbook: stampedPlaybook(options.playbook),
		run_receipt_bundle: options.runReceiptBundle ?? null,
		product_tool_receipts: productReceipts ?? null,
		product_assisted_eligible: productEligible(contestant, productReceipts, options.playbook),
		index_sha256: indexSha ?? "none",
		product_provenance: productReceipt ?? null,
		tokens_consumed: stats?.tokens.total,
		cost_usd: stats?.cost,
		model: `${contestant.provider}/${contestant.model}`,
		model_digest: options.measurementIdentity ? options.measurementIdentity.model_digest : modelDigest(contestant),
		pi_version: context.piVersion,
		prompt_sha256: sha256(prompt),
		contestant_config_sha256: stableHash({ contestant, tools: allowedTools ?? [...FIXED_TOOLS], thinking: FIXED_THINKING, budget_policy: context.budgetPolicy }),
		estate_sha256: context.estate.sha256,
		harness_sha256: context.harnessSha256,
		estate_repositories: context.estate.repositories,
		estate_revisions: context.estate.revisions,
		thinking: FIXED_THINKING,
		...(options.measurementIdentity ? { ...identityFields, measurement_identity: options.measurementIdentity } : {}),
		findings: answer.findings ?? {},
	});
}

export async function persistAttemptEvidence(outputDir: string, stem: string, startedAt: string, value: any) {
	await mkdir(outputDir, { recursive: true });
	const path = join(outputDir, `${stem}.${startedAt.replace(/[:.]/g, "-")}.attempt.json`);
	const bytes = `${JSON.stringify(value, null, 2)}\n`;
	await writeFile(path, bytes, { flag: "wx" });
	return { path, sha256: sha256(bytes) };
}

export async function runOnce(record: any, name: string, contestant: Contestant, estate: string, index: number, context: RunContext) {
	const runStartedAt = new Date().toISOString();
	const started = performance.now();
	const scratch = join(tmpdir(), `poc-run-${randomUUID()}`);
	const partial: any = { raw: "", stats: { tokens: { total: 0 }, cost: 0 }, productReceipts: [],
		reservations: [], exposure: { unknown_usage: false, pending_request: null, exposure_upper_bound_usd: 0 } };
	let runReceiptBundle: { path: string; sha256: string } | undefined;
	let attemptEvidence: { path: string; sha256: string } | undefined;
	let measurementIdentity: MeasurementIdentity | undefined;
	let campaignReservation: CampaignBudgetReservation | undefined;
	let campaignSettled = false, slotClaimed = false;
	let protectedUsage: { tokens_consumed: number; cost_usd: number; elapsed_seconds: number } | undefined;
	try {
		const template = await readFile(join(RUN_DIR, record.protocol_version === 2 ? "prompt-template-v2.md" : "prompt-template.md"), "utf8");
		const section = indexPromptSection(contestant.indexes ?? []);
		const prompt = buildPrompt(record, template) + (section ? `\n\n${section}` : "");
		partial.effectivePrompt = prompt;
		measurementIdentity = createMeasurementIdentity(name, contestant, index, context, prompt, context.campaign?.conditions.arms[name]?.index_sha256 ?? "none");
		if (record.evaluation_revision === "2.2" && !context.campaign) throw new Error("campaign preflight: evaluation revision 2.2 requires a frozen validated campaign slot");
		if (context.campaign) {
			if (record.evaluation_revision !== "2.2") throw new Error("campaign preflight: record evaluation revision differs from frozen campaign");
			if (!context.campaignRuntime) throw new Error("campaign preflight: missing current runtime digests");
			measurementIdentity.campaign_slot = preflightCampaignSlot(context.campaign,
				{ ...measurementIdentity, index_sha256: contestant.product || contestant.indexes?.length ? undefined : "none" },
				{ ...context.campaignRuntime, caseId: record.change_id, estateSha256: context.estate.sha256, budgetPolicy: context.budgetPolicy });
			campaignReservation = await reserveCampaignBudget(context.campaignClaimDir ?? join(RUNS, "v2"), context.campaign.conditions, measurementIdentity.campaign_slot, runStartedAt, context.budgetPolicy.max_cost_usd!);
			await claimCampaignSlot(context.campaignClaimDir ?? join(RUNS, "v2"), measurementIdentity.campaign_slot, runStartedAt);
			slotClaimed = true;
		}

		const remainingSeconds = context.budgetPolicy.timeout_seconds - (performance.now() - started) / 1000;
		if (remainingSeconds <= 0) throw new AttemptError("budget_exhausted", "total attempt deadline exhausted");
		const runtime = await superviseAttempt(fileURLToPath(import.meta.url), {
			prompt, contestant, estate, snapshot: context.estate, policy: context.budgetPolicy, protocol: record.protocol_version ?? 1,
			scratch, anchor: contestant.product_first ? deriveAnchor(record.proposed_change?.repo ?? "", record.proposed_change?.diff ?? "") : undefined,
			question: contestant.product_first ? record.query : undefined,
			campaign: context.campaign, measurementIdentity, declaredTools: declaredTools(contestant),
			campaignRuntime: context.campaignRuntime ? { ...context.campaignRuntime, caseId: record.change_id, estateSha256: context.estate.sha256, budgetPolicy: context.budgetPolicy } : undefined,
		}, { ...context.budgetPolicy, timeout_seconds: remainingSeconds }, (progress) => {
			if (progress.raw_delta !== undefined) partial.raw += progress.raw_delta;
			else Object.assign(partial, progress);
		});
		Object.assign(partial, runtime);
		if (context.campaign) preflightCampaignSlot(context.campaign, { ...measurementIdentity, index_sha256: runtime.indexSha },
			{ ...context.campaignRuntime!, caseId: record.change_id, estateSha256: context.estate.sha256, budgetPolicy: context.budgetPolicy });
		measurementIdentity.index_sha256 = runtime.indexSha;
		const elapsed = (performance.now() - started) / 1000;
		const usage = { tokens_consumed: runtime.stats.tokens.total, cost_usd: runtime.stats.cost, elapsed_seconds: elapsed };
		protectedUsage = usage;
		if (campaignReservation) {
			try { await settleCampaignBudget(context.campaignClaimDir ?? join(RUNS, "v2"), context.campaign!.conditions, campaignReservation, {cost_usd:usage.cost_usd},
				{exposure_upper_bound_usd:usage.cost_usd,unknown_usage:false});campaignSettled = true; }
			catch (error: any) { partial.campaign_budget_settlement_error = error.message; }
		}
		if (record.protocol_version === 2) runReceiptBundle = await persistRunReceipts(context.outputDir, record, name, runStartedAt,
			{ prompt: runtime.effectivePrompt, estateSha256: context.estate.sha256, productReceipt: runtime.productReceipt,
				productReceipts: runtime.productReceipts, playbook: runtime.playbook, budgetPolicy: context.budgetPolicy, usage, measurementIdentity });
		attemptEvidence = await persistAttemptEvidence(context.outputDir, `${record.change_id}-${name}-${index}`, runStartedAt, {
			protocol_version: record.protocol_version ?? 1, change_id: record.change_id, runner: name, run_started_at: runStartedAt,
			status: "runtime_completed", budget_policy: context.budgetPolicy, usage, run_receipt_bundle: runReceiptBundle ?? null, ...partial, measurement_identity: measurementIdentity, campaign_budget_reservation: campaignReservation ?? null,
		});
		let answer: any;
		try { answer = extractAnswer(runtime.raw); }
		catch (error: any) { throw new AttemptError("invalid_output", error.message); }
		return stampProvenance(answer, { record, name, contestant, index, prompt: runtime.effectivePrompt, elapsed,
			stats: runtime.stats, context, runStartedAt, toolCalls: runtime.toolCalls, toolTelemetry: runtime.toolTelemetry,
			indexSha: runtime.indexSha, productReceipt: runtime.productReceipt, productReceipts: runtime.productReceipts,
			allowedTools: runtime.allowedTools, playbook: runtime.playbook, reservations: runtime.reservations, runReceiptBundle, attemptEvidence, measurementIdentity });
	} catch (error: any) {
		if (!slotClaimed && (context.campaign || record.evaluation_revision === "2.2")) {
			if (campaignReservation && !campaignSettled) {
				try { await settleCampaignBudget(context.campaignClaimDir ?? join(RUNS, "v2"), context.campaign!.conditions, campaignReservation, {cost_usd:0}, {exposure_upper_bound_usd:0,unknown_usage:false});campaignSettled = true; }
				catch (settlementError: any) { partial.campaign_budget_settlement_error = settlementError.message; }
			}
			await mkdir(context.outputDir,{recursive:true});
			const path = join(context.outputDir,`${record.change_id}-${name}-${index}.${runStartedAt.replace(/[:.]/g,"-")}.preflight.json`);
			const bytes = JSON.stringify({protocol_version:2,change_id:record.change_id,runner:name,run_started_at:runStartedAt,status:"campaign_preflight_failed",reason:error.message,
				measurement_identity:measurementIdentity,campaign_budget_reservation:campaignReservation ?? null,campaign_budget_settlement_error:partial.campaign_budget_settlement_error ?? null,slot_claimed:false},null,2)+"\n";
			await writeFile(path,bytes,{flag:"wx"});
			throw new AttemptError("campaign_preflight_failed",error.message,{unrun:true,campaign_preflight:{path,sha256:sha256(bytes)}});
		}
		partial.index_observed_sha256 = partial.indexSha ?? partial.index_observed_sha256 ?? partial.productReceipt?.artifact_sha256 ?? null;
		if (measurementIdentity && !context.campaign) measurementIdentity.index_sha256 = partial.index_observed_sha256 ?? "none";
		const usage = protectedUsage ?? { tokens_consumed: partial.stats.tokens.total, cost_usd: partial.stats.cost,
			elapsed_seconds: (performance.now() - started) / 1000 };
		if (record.protocol_version === 2 && !runReceiptBundle) runReceiptBundle = await persistRunReceipts(
			context.outputDir, record, name, runStartedAt, { prompt: partial.effectivePrompt ?? "", estateSha256: context.estate.sha256,
				productReceipt: partial.productReceipt, productReceipts: partial.productReceipts, playbook: partial.playbook,
				budgetPolicy: context.budgetPolicy, usage, measurementIdentity });
		if (campaignReservation && !campaignSettled && !partial.campaign_budget_settlement_error) {
			try { await settleCampaignBudget(context.campaignClaimDir ?? join(RUNS,"v2"),context.campaign!.conditions,campaignReservation,{cost_usd:usage.cost_usd},partial.exposure);campaignSettled = true; }
			catch (error: any) { partial.campaign_budget_settlement_error = error.message; }
		}
		const evidence = attemptEvidence ?? await persistAttemptEvidence(context.outputDir, `${record.change_id}-${name}-${index}`, runStartedAt, {
			protocol_version: record.protocol_version ?? 1, change_id: record.change_id, runner: name,
			run_started_at: runStartedAt, status: error.status ?? "setup_failed", reason: error.message,
			budget_policy: context.budgetPolicy, usage, run_receipt_bundle: runReceiptBundle ?? null,
			...partial, measurement_identity: measurementIdentity, campaign_budget_reservation: campaignReservation ?? null, rejection_detail: error.evidence ?? null,
		});
		throw new AttemptError(error.status ?? "setup_failed", `${error.message}; attempt evidence: ${evidence.path}`, {
			attempt_evidence: evidence, run_started_at: runStartedAt, usage, exposure: partial.exposure, run_receipt_bundle: runReceiptBundle ?? null,
			budget_policy: context.budgetPolicy, measurement_identity: measurementIdentity, campaign_budget_reservation: campaignReservation ?? null,
		});
	} finally { await rm(scratch, { recursive: true, force: true }); }
}

export async function main(argv = process.argv.slice(2)) {
	const args = parseArgs(argv);
	const contestants: Record<string, Contestant> = await readJson(join(RUN_DIR, "contestants.json"));
	const chosen = args.contestant.length > 0 ? args.contestant : Object.keys(contestants).filter((name) => contestants[name].enabled !== false);
	const unknown = chosen.filter((name) => !(name in contestants));
	if (unknown.length > 0) throw new Error(`unknown contestant(s): ${unknown.join(", ")}`);

	const recordsDir = args.protocol === 2 ? join(RECORDS, "v2") : RECORDS;
	const outputDir = args.protocol === 2 ? join(RUNS, "v2", args.runGroup ?? "") : RUNS;
	const recordPaths = args.record.includes("all")
		? (await readdir(recordsDir)).filter((file) => file.endsWith(".json")).sort().map((file) => join(recordsDir, file))
		: args.record.map((id) => join(recordsDir, `${id}.json`));

	if (recordPaths.length === 0) throw new Error("no records selected for the requested protocol");
	const estate = resolve(args.estate);
	await assertDirectory(estate, "estate");
	const context: RunContext = {
		estate: await captureEstateSnapshot(estate),
		piVersion: (await readJson(PI_PACKAGE)).version,
		harnessSha256: await harnessHash(),
		outputDir,
		budgetPolicy: { timeout_seconds: args.timeout, max_tokens: args.maxTokens ?? null, max_cost_usd: args.maxCostUsd ?? null,
			cost_enforcement: "catalog_context_reservation", token_enforcement: "context_window_reservation", retries: false, compaction: false },
		oracleOptions: args.protocol === 2 ? {
			oracleBundle: resolve(args.oracleBundle!), trustedOracleSha256: args.trustedOracleSha256!, artifactRoot: resolve(args.artifactRoot!),
		} : undefined,
	};
	if (args.campaignSchedule) {
		context.campaign = await loadFrozenCampaign({ schedulePath: resolve(args.campaignSchedule), conditionsPath: resolve(args.campaignConditions!),
			trustedScheduleSha256: args.trustedCampaignScheduleSha256!, trustedConditionSha256: args.trustedCampaignConditionsSha256!, casesManifestPath: resolve(args.campaignCasesManifest!) });
		context.campaignRuntime = { scorerSha256: sha256(await readFile(join(HARNESS, "scoring", "score_v2.py"))),
			answerSchemaSha256: sha256(await readFile(join(HARNESS, "schema", "contestant-answer-v2.schema.json"))),
			taskTemplateSha256: sha256(await readFile(join(RUN_DIR, "prompt-template-v2.md"))),
			scoringPolicy: (await readJson(context.oracleOptions!.oracleBundle)).scoring_policy?.mode ?? "strict_reference_v1",
			oracleSha256: context.oracleOptions!.trustedOracleSha256,
			treatment: contestants[chosen[0]].product_first && contestants[chosen[0]].product ? "product-first" : !contestants[chosen[0]].product && !contestants[chosen[0]].indexes?.length ? "baseline" : "unsupported" };
	}
	// Fail the entire requested cohort before authenticating or creating a model.
	if (args.protocol === 2) {
		const snapshotSha = verifyPinnedSnapshot(estate, context.estate, await readJson(join(HARNESS, "indexes", "pins.json")));
		await assertVerifierOutsideEstate(estate, [context.oracleOptions!.oracleBundle, context.oracleOptions!.artifactRoot,
			...(args.campaignSchedule ? [resolve(args.campaignSchedule), resolve(args.campaignConditions!), resolve(args.campaignCasesManifest!)] : [])]);
		for (const recordPath of recordPaths) {
			validateFile("record", recordPath);
			const record = await readJson(recordPath);
			if (record.protocol_version !== 2 || record.status !== "verified") throw new Error(`v2 record is not independently verified: ${recordPath}`);
			if (record.snapshot_sha256 !== snapshotSha) throw new Error(`v2 record snapshot does not match pinned estate: ${recordPath}`);
			preflightV2(recordPath, context.oracleOptions!);
			if (record.evaluation_revision === "2.2" && !context.campaign) throw new Error("evaluation revision 2.2 requires frozen campaign inputs before provider initialization");
			if (context.campaign) {
				if (record.evaluation_revision !== "2.2") throw new Error("record evaluation revision differs from frozen campaign");
				context.campaignRuntime!.recordSha256 = sha256(await readFile(recordPath));
				const contestant = contestants[chosen[0]], template = await readFile(join(RUN_DIR, "prompt-template-v2.md"), "utf8");
				const section = indexPromptSection(contestant.indexes ?? []), prompt = buildPrompt(record, template) + (section ? `\n\n${section}` : "");
				const identity = createMeasurementIdentity(chosen[0], contestant, args.campaignRepeat!, context, prompt);
				preflightCampaignSlot(context.campaign, { ...identity, index_sha256: contestant.product || contestant.indexes?.length ? undefined : "none" },
					{ ...context.campaignRuntime!, caseId: record.change_id, estateSha256: context.estate.sha256, budgetPolicy: context.budgetPolicy });
			}

		}
	}
	await mkdir(outputDir, { recursive: true });
	if (args.protocol === 2) await assertVerifierOutsideEstate(estate, [outputDir]);
	console.log(`estate ${context.estate.sha256.slice(0, 12)} (${context.estate.repositories.length} repositories)`);

	let failures = 0;
	for (const recordPath of recordPaths) {
		validateFile("record", recordPath);
		const record = await readJson(recordPath);
		const requiredStatus = args.protocol === 2 ? "verified" : "frozen";
		if (record.status !== requiredStatus) {
			console.log(`skipping ${record.change_id}: status is ${record.status ?? "unset"}, only ${requiredStatus} records are scored`);
			continue;
		}
		for (const name of chosen) {
			for (let offset = 1; offset <= args.runs; offset++) {
				const index = args.campaignRepeat ?? offset;
				const stem = `${record.change_id}-${name}-${index}`;
				console.log(`\nrunning ${stem} ...`);
				let completedAnswer: any;
				let failureStage = "run_failed";
				try {
					const answer = completedAnswer = await runOnce(
						record, name, contestants[name], estate, index, context,
					);
					let answerPath = join(outputDir, `${stem}.json`);
					// Never overwrite a previous measurement undetectably. If the
					// stem already exists, suffix with the run timestamp instead.
					try {
						await stat(answerPath);
						const stamp = (answer.run_started_at ?? new Date().toISOString()).replace(/[:.]/g, "-");
						answerPath = join(outputDir, `${stem}.${stamp}.json`);
					} catch {
						// No previous file: use the canonical stem.
					}
					// Drop any score from a previous run of this stem before writing the new answer,
					// so a failure below cannot leave a stale score paired with a fresh answer.
					await rm(answerPath.replace(/\.json$/, ".score.json"), { force: true });
					await writeFile(answerPath, `${JSON.stringify(answer, null, 2)}\n`);
					failureStage = "invalid_output";
					validateFile("answer", answerPath);
					failureStage = "setup_failed";
					const scoringOptions = context.oracleOptions ? { ...context.oracleOptions,
						runReceiptBundle: answer.run_receipt_bundle.path,
						trustedRunReceiptsSha256: answer.run_receipt_bundle.sha256,
					} : undefined;
					console.log((await scoreAnswer(answerPath, recordPath, scoringOptions)).text);
				} catch (error) {
					failures++;
					const reason = (error as Error).message;
					console.log(`  FAILED: ${reason}`);
					if ((error as any).evidence?.unrun) { console.log("  Scheduled slot remains unrun; preflight diagnostic retained.");continue; }
					try {
						const usage = (error as any).evidence?.usage ?? (completedAnswer ? {
							tokens_consumed: completedAnswer.tokens_consumed, cost_usd: completedAnswer.cost_usd,
							elapsed_seconds: completedAnswer.elapsed_seconds } : null);
						const exposure = (error as any).evidence?.exposure ?? (completedAnswer ? {
							unknown_usage: false, pending_request: null, exposure_upper_bound_usd: completedAnswer.cost_usd } : null);
						const evidence = (error as any).evidence ?? (completedAnswer ? {
							attempt_evidence: completedAnswer.attempt_evidence, run_started_at: completedAnswer.run_started_at,
							run_receipt_bundle: completedAnswer.run_receipt_bundle, usage, exposure, budget_policy: context.budgetPolicy } : null);
						const stub = {
							protocol_version: args.protocol,
							status: (error as any).status ?? failureStage,
							evidence, usage, budget_exposure: exposure,
							budget_policy: context.budgetPolicy,
							change_id: record.change_id,
							runner: name,
							run_index: index,
							run_started_at: completedAnswer?.run_started_at ?? evidence?.run_started_at ?? new Date().toISOString(),
							reason,
							estate_sha256: context.estate.sha256,
							snapshot_sha256: record.snapshot_sha256 ?? null,
							measurement_identity: completedAnswer?.measurement_identity ?? evidence?.measurement_identity ?? null,
						};
						let stubPath = join(outputDir, `${stem}.rejected.json`);
						try {
							await stat(stubPath);
							const stamp = (stub.run_started_at as string).replace(/[:.]/g, "-");
							stubPath = join(outputDir, `${stem}.${stamp}.rejected.json`);
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
	if (args.protocol === 1) console.log(aggregate());
	else for (const recordPath of recordPaths) console.log(aggregateV2(recordPath, context.oracleOptions!, outputDir));
	if (failures > 0) {
		console.log(`\n${failures} run(s) failed and were not scored.`);
		process.exitCode = 1;
	}
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url) && process.argv[2] === "--attempt-worker") {
	void attemptWorker();
} else if (invokedPath === fileURLToPath(import.meta.url)) {
	main().catch((error) => {
		console.error(`FAILED: ${(error as Error).message}`);
		process.exitCode = 1;
	});
}
