#!/usr/bin/env node
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import {
	access as fsAccess,
	cp,
	glob,
	lstat,
	mkdir,
	mkdtemp,
	readFile,
	readdir,
	readlink,
	realpath,
	rm,
	stat,
	writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import {
	createAgentSession,
	createReadOnlyTools,
	DefaultResourceLoader,
	getAgentDir,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";

export const HARNESS = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const RUN_DIR = join(HARNESS, "run");
const RECORDS = join(HARNESS, "records");
const RUNS = join(HARNESS, "answers", "runs");
const SCORER = join(HARNESS, "scoring", "score.py");
const PI_ENTRY = fileURLToPath(import.meta.resolve("@earendil-works/pi-coding-agent"));
const PI_PACKAGE = resolve(dirname(PI_ENTRY), "..", "package.json");
const IGNORED = new Set(["target", "node_modules", ".git", "dist", "build"]);
export const ESTATE_REPOSITORIES = [
	"account-service",
	"basket-service",
	"catalog-service",
	"commerce-bff",
	"commerce-platform",
	"commerce-web",
	"inventory-service",
	"order-service",
	"payment-service",
	"shipment-service",
] as const;
export const FIXED_TOOLS = ["read", "grep", "find", "ls"] as const;
export const FIXED_THINKING = "off";

const repositoryRoot = resolve(HARNESS, "..", "..");
const siblingEstate = join(repositoryRoot, "POC-order-microservices");
export const DEFAULT_ESTATE = ESTATE_REPOSITORIES.every((name) => existsSync(join(siblingEstate, name)))
	? siblingEstate
	: repositoryRoot;

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

interface EstateRevision {
	name: string;
	commit: string | null;
	dirty: boolean | null;
}

interface EstateSnapshot {
	sha256: string;
	repositories: string[];
	revisions: EstateRevision[];
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

async function assertDirectory(path: string, label: string) {
	let value;
	try {
		value = await stat(path);
	} catch {
		throw new Error(`${label} not found: ${path}`);
	}
	if (!value.isDirectory()) throw new Error(`${label} is not a directory: ${path}`);
}

export async function isolate(
	estate: string,
	scratch: string,
	repositories: readonly string[] = ESTATE_REPOSITORIES,
) {
	await assertDirectory(estate, "estate");
	for (const name of repositories) {
		await assertDirectory(join(estate, name), `estate repository ${name}`);
	}
	const target = join(scratch, "estate");
	await mkdir(target);
	for (const name of repositories) {
		await cp(join(estate, name), join(target, name), {
			recursive: true,
			filter: (source) => !IGNORED.has(basename(source)),
		});
	}
	return target;
}

export async function createPathGuard(root: string) {
	const rootPath = await realpath(root);
	return async (candidate: string) => {
		const candidatePath = await realpath(candidate);
		const fromRoot = relative(rootPath, candidatePath);
		const outside = fromRoot === ".." || fromRoot.startsWith(`..${sep}`) || isAbsolute(fromRoot);
		if (outside) throw new Error(`path outside isolated estate: ${candidate}`);
		return candidatePath;
	};
}

export async function createRestrictedReadOnlyTools(root: string) {
	const inside = await createPathGuard(root);
	const tools = createReadOnlyTools(root, {
		read: {
			operations: {
				access: async (path) => fsAccess(await inside(path)),
				readFile: async (path) => readFile(await inside(path)),
			},
		},
		grep: {
			operations: {
				isDirectory: async (path) => (await stat(await inside(path))).isDirectory(),
				readFile: async (path) => readFile(await inside(path), "utf8"),
			},
		},
		find: {
			operations: {
				exists: async (path) => {
					try {
						await inside(path);
						return true;
					} catch {
						return false;
					}
				},
				glob: async (pattern, cwd, options) => {
					const safeCwd = await inside(cwd);
					const effectivePattern = pattern.includes("/") || pattern === "**" ? pattern : `**/${pattern}`;
					const results: string[] = [];
					for await (const match of glob(effectivePattern, { cwd: safeCwd })) {
						if (results.length >= options.limit) break;
						if (match.split("/").some((part) => IGNORED.has(part))) continue;
						const path = await inside(resolve(safeCwd, match));
						if ((await stat(path)).isFile()) results.push(path);
					}
					return results;
				},
			},
		},
		ls: {
			operations: {
				exists: async (path) => {
					try {
						await inside(path);
						return true;
					} catch {
						return false;
					}
				},
				stat: async (path) => stat(await inside(path)),
				readdir: async (path) => readdir(await inside(path)),
			},
		},
	});
	return tools;
}

async function fingerprintDirectory(root: string, hash = createHash("sha256"), prefix = "") {
	for (const entry of (await readdir(root, { withFileTypes: true })).sort((left, right) => left.name.localeCompare(right.name))) {
		if (IGNORED.has(entry.name)) continue;
		const path = join(root, entry.name);
		const relativePath = prefix ? `${prefix}/${entry.name}` : entry.name;
		const file = await lstat(path);
		hash.update(relativePath).update("\0");
		if (file.isDirectory()) await fingerprintDirectory(path, hash, relativePath);
		else if (file.isSymbolicLink()) hash.update(`symlink:${await readlink(path)}`).update("\0");
		else if (file.isFile()) hash.update(await readFile(path)).update("\0");
	}
	return hash;
}

function gitValue(repository: string, args: string[]) {
	const result = spawnSync("git", ["-C", repository, ...args], { encoding: "utf8" });
	return result.status === 0 ? result.stdout.trim() : null;
}

export async function captureEstateSnapshot(
	estate: string,
	repositories: readonly string[] = ESTATE_REPOSITORIES,
): Promise<EstateSnapshot> {
	const hash = createHash("sha256");
	const revisions: EstateRevision[] = [];
	for (const name of repositories) {
		const repository = join(estate, name);
		await assertDirectory(repository, `estate repository ${name}`);
		hash.update(name).update("\0");
		await fingerprintDirectory(repository, hash, name);
		const commit = gitValue(repository, ["rev-parse", "HEAD"]);
		const statusText = gitValue(repository, ["status", "--porcelain", "--untracked-files=all", "--", "."]);
		revisions.push({ name, commit, dirty: statusText === null ? null : statusText.length > 0 });
	}
	return { sha256: hash.digest("hex"), repositories: [...repositories], revisions };
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

function* jsonObjects(text: string) {
	for (let start = 0; start < text.length; start++) {
		if (text[start] !== "{") continue;
		let depth = 0;
		let inString = false;
		let escaped = false;
		for (let index = start; index < text.length; index++) {
			const current = text[index];
			if (inString) {
				if (escaped) escaped = false;
				else if (current === "\\") escaped = true;
				else if (current === '"') inString = false;
				continue;
			}
			if (current === '"') inString = true;
			else if (current === "{") depth++;
			else if (current === "}" && --depth === 0) {
				yield text.slice(start, index + 1);
				break;
			}
		}
	}
}

export function extractAnswer(raw: string) {
	let best: any;
	for (const candidate of jsonObjects(raw)) {
		try {
			const parsed = JSON.parse(candidate);
			if (parsed && typeof parsed === "object" && "findings" in parsed) best = parsed;
		} catch {
			continue;
		}
	}
	if (!best) throw new Error("no answer object with a 'findings' key in the output");
	return best;
}

export function validateFile(kind: "record" | "answer", path: string) {
	const result = spawnSync("python3", ["-B", SCORER, "validate", "--kind", kind, path], { encoding: "utf8" });
	if (result.error) throw result.error;
	if (result.status !== 0) {
		throw new Error(`${kind} schema validation failed: ${(result.stdout || result.stderr).trim().slice(0, 1200)}`);
	}
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
	const prompt = buildPrompt(record, template);
	const scratch = await mkdtemp(join(tmpdir(), "poc-run-"));
	let session: Awaited<ReturnType<typeof createAgentSession>>["session"] | undefined;
	let unsubscribe: (() => void) | undefined;
	let raw = "";
	let elapsed = 0;
	let stats: ReturnType<NonNullable<typeof session>["getSessionStats"]> | undefined;
	try {
		const workdir = await isolate(estate, scratch);
		const restrictedTools = await createRestrictedReadOnlyTools(workdir);
		({ session } = await createAgentSession({
			cwd: workdir,
			model,
			thinkingLevel: FIXED_THINKING,
			tools: [...FIXED_TOOLS],
			customTools: restrictedTools as any,
			modelRuntime,
			sessionManager: SessionManager.inMemory(workdir),
			settingsManager: SettingsManager.inMemory({}),
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
		elapsed = Math.round((performance.now() - started) / 100) / 10;
		stats = session.getSessionStats();
	} finally {
		unsubscribe?.();
		session?.dispose();
		await rm(scratch, { recursive: true, force: true });
	}

	let answer: any;
	try {
		answer = extractAnswer(raw);
	} catch (error) {
		await mkdir(RUNS, { recursive: true });
		const rawPath = join(RUNS, `${record.change_id}-${name}-${index}.raw.txt`);
		await writeFile(rawPath, raw);
		throw new Error(`${(error as Error).message}; raw output saved to ${rawPath}`);
	}
	answer.change_id = record.change_id;
	answer.contestant = contestant.label;
	answer.runner = name;
	answer.run_index = index;
	answer.elapsed_seconds = elapsed;
	answer.tokens_consumed = stats?.tokens.total;
	answer.cost_usd = stats?.cost;
	answer.model = `${contestant.provider}/${contestant.model}`;
	answer.model_digest = modelDigest(contestant);
	answer.pi_version = context.piVersion;
	answer.prompt_sha256 = sha256(prompt);
	answer.contestant_config_sha256 = stableHash({ contestant, tools: FIXED_TOOLS, thinking: FIXED_THINKING });
	answer.estate_sha256 = context.estate.sha256;
	answer.estate_repositories = context.estate.repositories;
	answer.estate_revisions = context.estate.revisions;
	answer.findings ??= {};
	return answer;
}

async function score(answerPath: string, recordPath: string) {
	const reportPath = answerPath.replace(/\.json$/, ".score.json");
	const result = spawnSync(
		"python3",
		["-B", SCORER, "score", "--ground-truth", recordPath, "--answer", answerPath, "--output", reportPath],
		{ encoding: "utf8" },
	);
	if (result.status !== 0) {
		throw new Error(`scoring failed: ${(result.stderr || result.stdout || "").trim().slice(0, 1200)}`);
	}
	return readJson(reportPath);
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

	const modelRuntime = await ModelRuntime.create();
	const outcomes: any[] = [];
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
				console.log(`running ${stem} ...`);
				try {
					const answer = await runOnce(
						record, name, contestants[name], modelRuntime, estate, index, args.timeout, context,
					);
					const answerPath = join(RUNS, `${stem}.json`);
					const reportPath = answerPath.replace(/\.json$/, ".score.json");
					await rm(reportPath, { force: true });
					await writeFile(answerPath, `${JSON.stringify(answer, null, 2)}\n`);
					validateFile("answer", answerPath);
					const report = await score(answerPath, recordPath);
					const metrics = report.metrics;
					console.log(
						`  recall ${metrics.cross_repo_recall}  precision ${metrics.precision}  ` +
						`composite ${metrics.composite}  ${answer.elapsed_seconds}s  ` +
						`${answer.tokens_consumed ?? "?"} tokens`,
					);
					outcomes.push({ run: stem, ...metrics });
				} catch (error) {
					console.log(`  FAILED: ${(error as Error).message}`);
					outcomes.push({ run: stem, error: (error as Error).message });
				}
			}
		}
	}

	console.log(JSON.stringify({ estate: context.estate, outcomes }, null, 2));
	if (outcomes.some((outcome) => "error" in outcome)) process.exitCode = 1;
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : "";
if (invokedPath === fileURLToPath(import.meta.url)) {
	main().catch((error) => {
		console.error(`FAILED: ${(error as Error).message}`);
		process.exitCode = 1;
	});
}
