import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { access, readFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { ESTATE_REPOSITORIES } from "./estate.ts";
import type { EstateSnapshot } from "./estate.ts";
import { HARNESS } from "./paths.ts";

export type ProductKind = "scip" | "gortex" | "graphify";

export interface ProductConfig {
	kind: ProductKind;
}

export interface ProductReceipt {
	mode: "real_product";
	product: string;
	version: string;
	commit: string | null;
	binary_sha256: string;
	artifact_sha256: string;
	manifest_sha256: string | null;
	query_surface: string[];
	freshness: "verified";
	adapter_version: string;
	config_sha256: string;
	index_built_at: string | null;
	index_duration_seconds: number | null;
	indexed_estate_sha256: string | null;
}

/** Bump when this adapter's query construction or normalization changes.
 *  1.1.0 — output persisted on every receipt, canonicalized reproducibility
 *  hash, SCIP FQN translation, Gortex route/symbol split.
 *  1.2.0 — output ceilings removed: an 8KB per-call bound discarded the tail
 *  of Gortex's richest answer, and with it three affected repositories. */
const ADAPTER_VERSION = "1.2.0";

function configSha(value: unknown) {
	return sha256(JSON.stringify(value));
}

const INDEXES = join(HARNESS, "indexes");
const PINS = join(INDEXES, "pins.json");
const MAX_OUTPUT = 1024 * 1024;

function sha256(value: string | Buffer) {
	return createHash("sha256").update(value).digest("hex");
}

async function fileSha(path: string) {
	return sha256(await readFile(path));
}

function executable(name: string, environmentName: string) {
	const configured = process.env[environmentName];
	if (configured) return configured;
	const result = spawnSync("which", [name], { encoding: "utf8" });
	if (result.status !== 0 || !result.stdout.trim()) throw new Error(`${name} is not installed`);
	return result.stdout.trim();
}

function run(binary: string, args: string[]) {
	const result = spawnSync(binary, args, { encoding: "utf8", maxBuffer: MAX_OUTPUT });
	if (result.error) throw result.error;
	const output = `${result.stdout ?? ""}${result.stderr ?? ""}`.trim();
	if (result.status !== 0) throw new Error(output || `${binary} exited ${result.status}`);
	return output || "No results.";
}

function result(text: string) {
	return { content: [{ type: "text", text }], details: {} };
}

function schema(properties: Record<string, any>, required: string[]) {
	return { type: "object", properties, required, additionalProperties: false } as any;
}

function stringEnum(values: string[], description?: string) {
	return { type: "string", enum: values, ...(description ? { description } : {}) };
}

function textParameter(description: string) {
	return { type: "string", minLength: 1, maxLength: 300, description };
}

function assertQuery(value: unknown, name: string) {
	if (typeof value !== "string" || value.trim().length === 0 || value.length > 300) {
		throw new Error(`${name} must be 1-300 characters`);
	}
	return value.trim();
}

/** An HTTP route, not a symbol. Enforced so route_impact and symbol_impact
 *  cannot be confused at the call site the way bridge_impact allowed. */
function assertRoute(value: unknown) {
	const route = assertQuery(value, "query");
	if (!/^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+\/\S*$/.test(route)) {
		throw new Error('route must read "<METHOD> /path", for example "POST /api/v1/addresses"; use symbol_impact for a code symbol');
	}
	return route;
}

/** A code symbol, not a route. */
function assertSymbol(value: unknown) {
	const symbol = assertQuery(value, "query");
	if (/^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+\//.test(symbol) || symbol.startsWith("/")) {
		throw new Error("symbol_impact takes a code symbol, not an HTTP route; use route_impact for a route");
	}
	return symbol;
}

function assertRepo(value: unknown) {
	if (typeof value !== "string" || !(ESTATE_REPOSITORIES as readonly string[]).includes(value)) {
		throw new Error("repo must be one of the pinned estate repositories");
	}
	return value;
}

export interface ProductInvocationReceipt {
	id: string;
	tool: string;
	operation: string;
	// Normalized arguments as validated by the wrapper — not raw model text.
	parameters: Record<string, unknown>;
	// What the model asked for vs what the product was actually run with.
	// They differ where an adapter translates dialects; see scipTool.
	requested_query: string | null;
	executed_query: string | null;
	success: boolean;
	// The bounded text the model saw, stored verbatim. A hash alone cannot be
	// audited: it proves two things match, never what either one said.
	output: string | null;
	output_sha256: string | null;
	output_bytes: number | null;
	// Equal to output_bytes while nothing is bounded; kept so a future ceiling
	// makes its loss visible instead of silent.
	output_full_bytes: number | null;
	truncated: boolean;
	// Same output with unstable ordering canonicalized, so that reproducibility
	// is judged on content. Gortex reorders equal nodes between identical calls.
	output_normalized: string | null;
	output_normalized_sha256: string | null;
	duration_ms: number;
	error: string | null;
}

// Output is stored and passed on whole. Bounding it to 8KB per call cost
// Gortex 5 of the 8 ground-truth items in its richest single answer, because
// head-truncation discards the tail and Gortex's tail held three of the four
// affected repositories. The only remaining ceiling is the 1MB spawn buffer in
// run(), which surfaces as a failed receipt rather than a silent trim.

/** Sort JSON arrays of objects by a stable key so ordering noise stops
 *  registering as semantic change. Non-JSON output is returned unchanged. */
export function canonicalizeOutput(text: string) {
	let parsed: unknown;
	try {
		parsed = JSON.parse(text);
	} catch {
		return text;
	}
	const walk = (value: any): any => {
		if (Array.isArray(value)) {
			const items = value.map(walk);
			return items.every((item) => item && typeof item === "object" && !Array.isArray(item))
				? items.slice().sort((a, b) => (JSON.stringify(a) < JSON.stringify(b) ? -1 : 1))
				: items;
		}
		if (value && typeof value === "object") {
			return Object.fromEntries(Object.keys(value).sort().map((key) => [key, walk(value[key])]));
		}
		return value;
	};
	return JSON.stringify(walk(parsed));
}

/** scip-search matches partial symbol names and returns nothing for a
 *  fully-qualified one. The prompt's Identifier Convention trains the model on
 *  canonical FQNs, so it asked in a dialect the product does not answer: every
 *  `references` call in the REST-001 pilot came back empty. Translate rather
 *  than reject, and record both forms on the receipt. */
export function scipQueryName(requested: string) {
	const trimmed = requested.trim();
	if (/\s/.test(trimmed) || !trimmed.includes(".")) return trimmed;
	const segments = trimmed.split(".").filter(Boolean);
	// A canonical Java FQN ends in the declaring type, optionally then a member.
	// Take the last segment starting upper-case; fall back to the final segment.
	const typeIndex = segments.map((s) => /^[A-Z]/.test(s)).lastIndexOf(true);
	return typeIndex === -1 ? segments[segments.length - 1] : segments[typeIndex];
}

function scipTool(binary: string, index: string, receipts: ProductInvocationReceipt[], takeId: () => string) {
	const operations = ["symbols", "references", "implementations", "graph", "callers", "callees", "impact"];
	return {
		name: "scip_search",
		label: "SCIP search",
		description: "Query the real aggregated scip-java index through scip-search. A fully-qualified name is accepted and narrowed to its declaring type, which is the form the index matches. Results are product-direct evidence; cross-service connections absent from the output are not.",
		parameters: schema({
			operation: stringEnum(operations),
			name: textParameter("Symbol name. A partial name or a fully-qualified name both work."),
		}, ["operation", "name"]),
		execute: async (_id: string, params: any) => {
			const operation = assertQuery(params.operation, "operation");
			if (!operations.includes(operation)) throw new Error("unsupported SCIP operation");
			const requested = assertQuery(params.name, "name");
			const executed = scipQueryName(requested);
			return invokeWithReceipt(receipts, takeId, "scip_search", operation, { operation, name: requested },
				() => result(run(binary, [operation, "--index", index, "--name", executed, "--json"])),
				{ requested, executed });
		},
	};
}

/** Prefix a tool result with its receipt id, so a product_direct finding can cite it. */
function stampReceiptId(value: any, id: string) {
	const block = value?.content?.[0];
	if (block?.type === "text") block.text = `[receipt ${id}]\n${block.text}`;
	return value;
}

/** Run a product invocation, recording a runner-generated receipt.
 *
 * The receipt — not the model's later claims — is what ties a
 * product_direct finding to evidence. Failures are recorded with the
 * error message and rethrown so tool behavior is unchanged.
 */
async function invokeWithReceipt(
	receipts: ProductInvocationReceipt[],
	takeId: () => string,
	tool: string,
	operation: string,
	parameters: Record<string, unknown>,
	invoke: () => unknown,
	queries: { requested?: string; executed?: string } = {},
) {
	const started = performance.now();
	const id = takeId();
	try {
		const value: any = await invoke();
		// Bound before the model sees it, so the receipt stores exactly the
		// bytes that reached the model — not a longer text it never read.
		const output = value?.content?.[0]?.text ?? "";
		const full = output;
		const truncated = false;
		// Canonicalize the COMPLETE output, never the truncated one: two
		// differently-ordered responses cut at the same byte offset leave
		// different fragments, and a fragment is not parseable JSON, so
		// canonicalization would silently pass it through unsorted. The
		// normalized hash judges reproducibility and must see everything.
		const normalizedFull = canonicalizeOutput(full);
		const normalized = normalizedFull;
		receipts.push({
			id, tool, operation, parameters,
			requested_query: queries.requested ?? null,
			executed_query: queries.executed ?? null,
			success: true,
			output,
			output_sha256: sha256(JSON.stringify(value) ?? ""),
			output_bytes: Buffer.byteLength(output),
			output_full_bytes: Buffer.byteLength(full),
			truncated,
			output_normalized: normalized,
			// Covers the full canonical output, so it stays comparable across
			// calls even when the stored copies above are both truncated.
			output_normalized_sha256: sha256(normalizedFull),
			duration_ms: Math.round((performance.now() - started) * 10) / 10,
			error: null,
		});
		// Hashed above, stamped after: output_sha256 covers the product's own
		// output, and the model still sees the id it must cite to claim
		// product_direct.
		return stampReceiptId(value, id);
	} catch (error) {
		receipts.push({
			id, tool, operation, parameters,
			requested_query: queries.requested ?? null,
			executed_query: queries.executed ?? null,
			success: false,
			output: null, output_sha256: null, output_bytes: null, output_full_bytes: null, truncated: false,
			output_normalized: null, output_normalized_sha256: null,
			duration_ms: Math.round((performance.now() - started) * 10) / 10,
			error: String((error as Error)?.message ?? error).slice(0, 500),
		});
		throw error;
	}
}

function graphifyTool(binary: string, graph: string, receipts: ProductInvocationReceipt[], takeId: () => string) {
	const operations = ["query", "explain", "path", "affected"];
	return {
		name: "graphify_query",
		label: "Graphify query",
		description: "Query the real merged Graphify graph using its native read-only commands.",
		parameters: schema({
			operation: stringEnum(operations),
			query: textParameter("Question or source node."),
			target: { type: "string", maxLength: 300, description: "Target node; required only for path." },
		}, ["operation", "query"]),
		execute: async (_id: string, params: any) => {
			const operation = assertQuery(params.operation, "operation");
			if (!operations.includes(operation)) throw new Error("unsupported Graphify operation");
			const query = assertQuery(params.query, "query");
			const args = operation === "path"
				? [operation, query, assertQuery(params.target, "target"), "--graph", graph]
				: [operation, query, "--graph", graph];
			const parameters = operation === "path" ? { operation, query, target: params.target } : { operation, query };
			return invokeWithReceipt(receipts, takeId, "graphify_query", operation, parameters, () => result(run(binary, args)));
		},
	};
}

export function verifyHeads(estate: string, pins: any) {
	// Fast per-query drift guard: the full clean/dirty check runs once at
	// setup; here only HEAD movement matters, since the model holds
	// read-only tools and nothing else should touch the estate mid-run.
	for (const name of ESTATE_REPOSITORIES) {
		const result = spawnSync("git", ["-C", join(estate, name), "rev-parse", "HEAD"], { encoding: "utf8" });
		const head = (result.stdout ?? "").trim();
		if (result.status !== 0 || head !== pins.repositories[name]?.commit) {
			throw new Error(`Gortex estate drifted mid-run at ${name}: expected ${pins.repositories[name]?.commit}, got ${head || "unreadable"}`);
		}
	}
}

function gortexTool(binary: string, estate: string, pins: any, receipts: ProductInvocationReceipt[], takeId: () => string) {
	const operations = ["symbol", "usages", "callers", "calls", "dependents", "deps", "implementations"];
	return {
		name: "gortex_query",
		label: "Gortex query",
		description: "Query the real Gortex knowledge graph in the pinned poc-estate workspace.",
		parameters: schema({
			operation: stringEnum(operations),
			query: textParameter("Symbol name or exact graph node id."),
			repo: stringEnum([...ESTATE_REPOSITORIES], "Repository used as the query view."),
		}, ["operation", "query", "repo"]),
		execute: async (_id: string, params: any) => {
			verifyHeads(estate, pins);
			const operation = assertQuery(params.operation, "operation");
			if (!operations.includes(operation)) throw new Error("unsupported Gortex operation");
			const query = assertQuery(params.query, "query");
			const repo = assertRepo(params.repo);
			return invokeWithReceipt(receipts, takeId, "gortex_query", operation, { operation, query, repo }, () =>
				result(run(binary, ["query", operation, query, "--format", "json", "--limit", "100", "--index", join(estate, repo)])));
		},
	};
}

function gortexContractsTool(binary: string, estate: string, pins: any, receipts: ProductInvocationReceipt[], takeId: () => string) {
	// route_impact and symbol_impact were one `bridge_impact` action taking a
	// free-text `query`. The model fed it HTTP routes, which it answers with
	// "symbol not found" — 6 of 22 Gortex calls in the REST-001 pilot. Separate
	// operations make the argument's kind unmistakable at the call site.
	const actions = ["list", "check", "validate", "bridge_rank", "symbol_impact", "route_impact"];
	return {
		name: "gortex_contracts",
		label: "Gortex contracts",
		description: "Use Gortex's native read-only contract bridge or fused API-impact analysis across the pinned workspace. Use route_impact for an HTTP route and symbol_impact for a code symbol; they are not interchangeable.",
		parameters: schema({
			action: stringEnum(actions),
			query: { type: "string", maxLength: 300, description: "An HTTP route for route_impact, a code symbol for symbol_impact, a bridge query for bridge_rank. Omit for list, check and validate." },
			repo: stringEnum([...ESTATE_REPOSITORIES], "Repository used as the query view."),
		}, ["action", "repo"]),
		execute: async (_id: string, params: any) => {
			verifyHeads(estate, pins);
			const action = assertQuery(params.action, "action");
			if (!actions.includes(action)) throw new Error("unsupported Gortex contract operation");
			const repo = assertRepo(params.repo);
			const indexArgs = ["--index", join(estate, repo), "--format", "json"];
			const parameters: Record<string, unknown> = { action, repo };
			let executed: string | undefined;
			const invoke = () => {
				if (action === "route_impact") {
					const route = assertRoute(params.query);
					parameters.query = route;
					executed = route;
					return result(run(binary, ["call", "api_impact", "--arg", `route=${route}`, "--arg", `repo=${repo}`, ...indexArgs]));
				}
				const contractAction = action.startsWith("bridge_") || action === "symbol_impact" ? "bridge" : action;
				const args = ["call", "contracts", "--arg", `action=${contractAction}`];
				if (action === "bridge_rank") {
					const query = assertQuery(params.query, "query");
					args.push("--arg", "mode=rank", "--arg", `query=${query}`);
					parameters.query = query;
					executed = query;
				} else if (action === "symbol_impact") {
					const symbol = assertSymbol(params.query);
					args.push("--arg", "mode=impact", "--arg", `symbol=${symbol}`);
					parameters.query = symbol;
					executed = symbol;
				} else {
					args.push("--arg", `repo=${repo}`);
				}
				return result(run(binary, [...args, ...indexArgs]));
			};
			return invokeWithReceipt(receipts, takeId, "gortex_contracts", action, parameters, invoke,
				{ requested: params.query, executed });
		},
	};
}

function verifyEstate(snapshot: EstateSnapshot, pins: any) {
	const failures: string[] = [];
	for (const revision of snapshot.revisions) {
		const expected = pins.repositories[revision.name];
		if (!expected) failures.push(`${revision.name}: not pinned`);
		else if (revision.commit !== expected.commit || revision.dirty !== expected.dirty) {
			failures.push(`${revision.name}: expected ${expected.commit} clean, got ${revision.commit} dirty=${revision.dirty}`);
		}
	}
	if (snapshot.revisions.length !== Object.keys(pins.repositories).length) failures.push("repository set differs from pins");
	if (failures.length) throw new Error(`real-product freshness check failed: ${failures.join("; ")}`);
}

async function verifyManifest(path: string, expectedProduct: string) {
	const bytes = await readFile(path);
	const manifest = JSON.parse(bytes.toString("utf8"));
	if (manifest.product !== expectedProduct) throw new Error(`wrong product in ${path}`);
	if (manifest.pins_sha256 !== await fileSha(PINS)) throw new Error(`manifest was built from different pins: ${path}`);
	for (const artifact of manifest.artifacts) {
		const artifactPath = resolve(INDEXES, artifact.path);
		await access(artifactPath);
		if (await fileSha(artifactPath) !== artifact.sha256) throw new Error(`stale product artifact: ${artifact.name}`);
	}
	return { manifest, sha256: sha256(bytes) };
}

export async function createRealProduct(
	config: ProductConfig,
	estate: string,
	snapshot: EstateSnapshot,
): Promise<{ tools: any[]; receipt: ProductReceipt; prompt: string; receipts: ProductInvocationReceipt[] }> {
	const pinsBytes = await readFile(PINS);
	const pins = JSON.parse(pinsBytes.toString("utf8"));
	verifyEstate(snapshot, pins);
	const receipts: ProductInvocationReceipt[] = [];
	let receiptSeq = 0;
	const takeId = () => `r${++receiptSeq}`;
	if (config.kind === "scip") {
		const binary = executable("scip-search", "SCIP_SEARCH_BIN");
		if (await fileSha(binary) !== pins.toolchain["scip-search-sha256"]) throw new Error("scip-search binary SHA-256 differs from pins");
		const path = join(INDEXES, "manifests", "scip.json");
		const verified = await verifyManifest(path, "scip-java+scip-search");
		const index = join(INDEXES, "scip", "estate.scip");
		return {
			tools: [scipTool(binary, index, receipts, takeId)],
			receipt: {
				mode: "real_product", product: "scip-java+scip-search", version: `${pins.toolchain["scip-java"]}+${pins.toolchain["scip-search"]}`,
				commit: pins.toolchain["scip-search-commit"], binary_sha256: pins.toolchain["scip-search-sha256"],
				artifact_sha256: verified.manifest.artifacts.find((item: any) => item.name === "estate").sha256,
				manifest_sha256: verified.sha256, query_surface: ["symbols", "references", "implementations", "graph", "callers", "callees", "impact"], freshness: "verified",
				adapter_version: ADAPTER_VERSION,
				config_sha256: configSha({ product: "scip-java+scip-search", index: "scip/estate.scip", query_surface: ["symbols", "references", "implementations", "graph", "callers", "callees", "impact"] }),
				index_built_at: verified.manifest.metadata.built_at ?? null,
				index_duration_seconds: verified.manifest.metadata.duration_seconds ?? null,
				indexed_estate_sha256: snapshot.sha256 ?? null,
			},
			prompt: "Use scip_search for compiler-index facts. Attribute only facts present in its output as product_direct; bridge across services as agent_inferred.",
			receipts,
		};
	}
	if (config.kind === "graphify") {
		const binary = executable("graphify", "GRAPHIFY_BIN");
		const version = run(binary, ["--version"]);
		if (!version.includes(pins.toolchain.graphify)) throw new Error("Graphify version differs from pins");
		const path = join(INDEXES, "manifests", "graphify.json");
		const verified = await verifyManifest(path, "graphify");
		const graph = join(INDEXES, "graphify", "merged-graph.json");
		return {
			tools: [graphifyTool(binary, graph, receipts, takeId)],
			receipt: {
				mode: "real_product", product: "graphify", version: pins.toolchain.graphify, commit: null,
				binary_sha256: pins.toolchain["graphify-package-tree-sha256"],
				artifact_sha256: verified.manifest.artifacts.find((item: any) => item.name === "merged_graph").sha256,
				manifest_sha256: verified.sha256, query_surface: ["query", "explain", "path", "affected"], freshness: "verified",
				adapter_version: ADAPTER_VERSION,
				config_sha256: configSha({ product: "graphify", graph: "graphify/merged-graph.json", query_surface: ["query", "explain", "path", "affected"] }),
				index_built_at: verified.manifest.metadata.built_at ?? null,
				index_duration_seconds: verified.manifest.metadata.duration_seconds ?? null,
				indexed_estate_sha256: snapshot.sha256 ?? null,
			},
			prompt: "Use graphify_query for graph facts. Attribute only nodes and paths in its output as product_direct; conclusions you derive are agent_inferred.",
			receipts,
		};
	}
	const binary = executable("gortex", "GORTEX_BIN");
	if (await fileSha(binary) !== pins.toolchain["gortex-sha256"]) throw new Error("Gortex binary SHA-256 differs from pins");
	const admin = join(INDEXES, "index_admin.py");
	run("python3", [admin, "verify-gortex", "--estate", estate, "--binary", binary, "--require-daemon"]);
	const artifactSha = sha256(JSON.stringify({ repositories: pins.repositories, workspace: pins.toolchain["gortex-workspace"] }));
	return {
		tools: [gortexTool(binary, estate, pins, receipts, takeId), gortexContractsTool(binary, estate, pins, receipts, takeId)],
		receipt: {
			mode: "real_product", product: "gortex", version: pins.toolchain.gortex,
			commit: pins.toolchain["gortex-commit"], binary_sha256: pins.toolchain["gortex-sha256"],
			artifact_sha256: artifactSha, manifest_sha256: null,
			query_surface: ["symbol", "usages", "callers", "calls", "dependents", "deps", "implementations", "contracts", "route_impact", "symbol_impact"], freshness: "verified",
			adapter_version: ADAPTER_VERSION,
			config_sha256: configSha({ product: "gortex", workspace: pins.toolchain["gortex-workspace"], query_surface: ["symbol", "usages", "callers", "calls", "dependents", "deps", "implementations", "contracts", "route_impact", "symbol_impact"] }),
			index_built_at: null,
			index_duration_seconds: null,
			indexed_estate_sha256: snapshot.sha256 ?? null,
		},
			prompt: "Use gortex_query for graph facts. Attribute only returned nodes and edges as product_direct; conclusions you derive are agent_inferred.",
			receipts,
		};
	}
