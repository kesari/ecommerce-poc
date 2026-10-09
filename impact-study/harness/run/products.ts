import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { access, readFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { ESTATE_REPOSITORIES } from "./estate.ts";
import type { EstateSnapshot } from "./estate.ts";
import { HARNESS } from "./paths.ts";

export type ProductKind = "scip" | "gortex" | "graphify" | "repowise" | "codebase-memory";

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
const ADAPTER_VERSION = "1.4.0";

function configSha(value: unknown) {
	return sha256(JSON.stringify(value));
}

const INDEXES = join(HARNESS, "indexes");
const PINS = join(INDEXES, "pins.json");
const PRODUCT_PINS = join(INDEXES, "product-pins");
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

function run(binary: string, args: string[], options: { cwd?: string; env?: NodeJS.ProcessEnv } = {}) {
	const result = spawnSync(binary, args, { encoding: "utf8", maxBuffer: MAX_OUTPUT, timeout: 60_000, ...options });
	if (result.error) throw result.error;
	const output = `${result.stdout ?? ""}${result.stderr ?? ""}`.trim();
	if (result.status !== 0) throw new Error(output || `${binary} exited ${result.status}`);
	return output || "No results.";
}

async function productExecutable(localPath: string, command: string, environmentName: string) {
	if (process.env[environmentName]) return process.env[environmentName] as string;
	try {
		await access(localPath);
		return localPath;
	} catch {
		return executable(command, environmentName);
	}
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

function assertDepth(value: unknown) {
	if (!Number.isInteger(value) || Number(value) < 1 || Number(value) > 8) {
		throw new Error("max_depth must be an integer from 1 to 8");
	}
	return Number(value);
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

function repowiseSearchTool(binary: string, workspace: string, receipts: ProductInvocationReceipt[], takeId: () => string) {
	const modes = ["symbol", "path"];
	return {
		name: "repowise_search",
		label: "RepoWise search",
		description: "Query the real RepoWise workspace index across one repository or the complete estate.",
		parameters: schema({
			query: textParameter("Identifier, path, or code concept to search for."),
			mode: stringEnum(modes),
			repo: stringEnum(["all", ...ESTATE_REPOSITORIES], "Repository alias, or all for a workspace-wide search."),
		}, ["query", "mode", "repo"]),
		execute: async (_id: string, params: any) => {
			const query = assertQuery(params.query, "query");
			const mode = assertQuery(params.mode, "mode");
			if (!modes.includes(mode)) throw new Error("unsupported RepoWise search mode");
			const repo = params.repo === "all" ? "all" : assertRepo(params.repo);
			const args = ["search", query, workspace, "--mode", mode, "--limit", "10", "--format", "json"];
			args.push(repo === "all" ? "--all" : "--repo", ...(repo === "all" ? [] : [repo]));
			return invokeWithReceipt(receipts, takeId, "repowise_search", mode, { query, mode, repo },
				() => result(run(binary, args)), { requested: query, executed: query });
		},
	};
}

export function repowiseJson(output: string) {
	const start = output.indexOf("{");
	if (start < 0) throw new Error("RepoWise returned no JSON payload");
	let depth = 0;
	let quoted = false;
	let escaped = false;
	for (let index = start; index < output.length; index++) {
		const character = output[index];
		if (quoted) {
			if (escaped) escaped = false;
			else if (character === "\\") escaped = true;
			else if (character === '"') quoted = false;
			continue;
		}
		if (character === '"') quoted = true;
		else if (character === "{") depth += 1;
		else if (character === "}") {
			depth -= 1;
			if (depth === 0) return JSON.parse(output.slice(start, index + 1));
		}
	}
	throw new Error("RepoWise returned incomplete JSON");
}

function repowiseContextTool(binary: string, workspace: string, receipts: ProductInvocationReceipt[], takeId: () => string) {
	return {
		name: "repowise_context",
		label: "RepoWise symbol context",
		description: "Resolve a symbol in RepoWise and return its native caller, callee, and usage context.",
		parameters: schema({
			query: textParameter("Exact symbol name to resolve."),
			repo: stringEnum([...ESTATE_REPOSITORIES], "Repository alias containing the changed symbol."),
		}, ["query", "repo"]),
		execute: async (_id: string, params: any) => {
			const query = assertQuery(params.query, "query");
			const repo = assertRepo(params.repo);
			return invokeWithReceipt(receipts, takeId, "repowise_context", "symbol_context", { query, repo }, () => {
				const lookupText = run(binary, ["search", query, workspace, "--mode", "symbol", "--limit", "10", "--format", "json", "--repo", repo]);
				const lookup = repowiseJson(lookupText);
				const matches = Array.isArray(lookup.results) ? lookup.results : [];
				const exact = matches.find((item: any) => item?.name === query && ["class", "interface", "record", "enum"].includes(item?.kind))
					?? matches.find((item: any) => item?.name === query);
				if (!exact?.symbol_id) return result(JSON.stringify({ lookup, context: null }));
				const contextText = run(binary, ["context", exact.symbol_id, "--include", "callers", "--include", "callees", "--path", workspace, "--repo", repo, "--format", "json"]);
				return result(JSON.stringify({ lookup, context: repowiseJson(contextText) }));
			}, { requested: query, executed: query });
		},
	};
}

function repowiseBlastRadiusTool(baseUrl: string, receipts: ProductInvocationReceipt[], takeId: () => string) {
	return {
		name: "repowise_blast_radius",
		label: "RepoWise blast radius",
		description: "Call RepoWise's native workspace blast-radius endpoint for cross-repository structural and behavioral impact.",
		parameters: schema({
			target: stringEnum([...ESTATE_REPOSITORIES], "Repository alias to expand from."),
			max_depth: { type: "integer", minimum: 1, maximum: 8 },
			include_behavioral: { type: "boolean" },
		}, ["target", "max_depth", "include_behavioral"]),
		execute: async (_id: string, params: any) => {
			const target = assertRepo(params.target);
			const maxDepth = assertDepth(params.max_depth);
			const includeBehavioral = Boolean(params.include_behavioral);
			const url = new URL("/api/workspace/blast-radius", baseUrl);
			url.searchParams.set("target", target);
			url.searchParams.set("max_depth", String(maxDepth));
			url.searchParams.set("include_behavioral", String(includeBehavioral));
			return invokeWithReceipt(receipts, takeId, "repowise_blast_radius", "blast_radius",
				{ target, max_depth: maxDepth, include_behavioral: includeBehavioral }, async () => {
					const response = await fetch(url, { signal: AbortSignal.timeout(30_000) });
					const body = await response.text();
					if (!response.ok) throw new Error(`RepoWise HTTP ${response.status}: ${body.slice(0, 500)}`);
					return result(body || "No results.");
				});
		},
	};
}

function regexLiteral(value: string) {
	return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function cypherLiteral(value: string) {
	return value.replace(/\\/g, "\\\\").replace(/'/g, "\\'");
}

export function codebaseMemoryRelationQuery(query: string, relation: string) {
	if (!/^[A-Z][A-Z0-9_]*$/.test(relation)) throw new Error("invalid Codebase Memory relationship type");
	const literal = cypherLiteral(query);
	return `MATCH (a)-[r:${relation}]->(b) WHERE (a.name CONTAINS '${literal}' OR a.qualified_name CONTAINS '${literal}' OR b.name CONTAINS '${literal}' OR b.qualified_name CONTAINS '${literal}') RETURN a.name, labels(a), type(r), b.name, labels(b), properties(r) LIMIT 100`;
}

function codebaseMemoryTool(binary: string, cache: string, receipts: ProductInvocationReceipt[], takeId: () => string) {
	const operations = ["search", "references", "trace", "cross_repo", "tests", "schema"];
	const environment = { ...process.env, CBM_CACHE_DIR: cache };
	return {
		name: "codebase_memory_query",
		label: "Codebase Memory query",
		description: "Query the real Codebase Memory graph using its one-shot CLI, including CROSS_* edges created by its cross-repo indexing pass.",
		parameters: schema({
			operation: stringEnum(operations),
			query: textParameter("Symbol, route, topic, or other changed identifier."),
			repo: stringEnum([...ESTATE_REPOSITORIES], "Indexed Codebase Memory project."),
		}, ["operation", "query", "repo"]),
			execute: async (_id: string, params: any) => {
			const operation = assertQuery(params.operation, "operation");
			if (!operations.includes(operation)) throw new Error("unsupported Codebase Memory operation");
			const query = assertQuery(params.query, "query");
			const repo = assertRepo(params.repo);
			let tool: string;
			let args: string[];
			if (operation === "search") {
				tool = "search_graph";
				args = ["--project", repo, "--name-pattern", `.*${regexLiteral(query)}.*`, "--format", "json"];
			} else if (operation === "trace") {
				tool = "trace_path";
				args = ["--project", repo, "--function-name", query, "--direction", "both", "--max-depth", "5", "--format", "json"];
			} else if (operation === "references") {
				tool = "query_graph";
				const literal = cypherLiteral(query);
				const cypher = `MATCH (a)-[r]->(b) WHERE (a.name CONTAINS '${literal}' OR a.qualified_name CONTAINS '${literal}' OR b.name CONTAINS '${literal}' OR b.qualified_name CONTAINS '${literal}') RETURN a.name, labels(a), type(r), b.name, labels(b), properties(r) LIMIT 100`;
				args = ["--project", repo, "--query", cypher, "--format", "json"];
			} else if (operation === "schema") {
				tool = "get_graph_schema";
				args = ["--project", repo, "--format", "json"];
			} else if (operation === "tests") {
				tool = "query_graph";
				args = ["--project", repo, "--query", codebaseMemoryRelationQuery(query, "TESTS"), "--format", "json"];
			} else {
				tool = "query_graph";
				args = [];
			}
			return invokeWithReceipt(receipts, takeId, "codebase_memory_query", operation,
				{ operation, query, repo }, () => {
					if (operation !== "cross_repo") {
						return result(run(binary, ["cli", "--quiet", tool, ...args], { env: environment }));
					}
					const schemaText = run(binary, ["cli", "--quiet", "get_graph_schema", "--project", repo, "--format", "json"], { env: environment });
					const graphSchema = JSON.parse(schemaText);
					const edgeTypes = (Array.isArray(graphSchema.edge_types) ? graphSchema.edge_types : [])
						.map((edge: any) => edge?.type)
						.filter((edge: unknown): edge is string => typeof edge === "string" && /^CROSS_[A-Z0-9_]+$/.test(edge));
					const responses = edgeTypes.map((edgeType: string) => {
						const output = run(binary, ["cli", "--quiet", "query_graph", "--project", repo, "--query", codebaseMemoryRelationQuery(query, edgeType), "--format", "json"], { env: environment });
						return { edge_type: edgeType, response: JSON.parse(output) };
					});
					return result(JSON.stringify({ edge_types: edgeTypes, results: responses }));
				},
				{ requested: query, executed: query });
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

async function readProductPin(name: string) {
	const path = join(PRODUCT_PINS, `${name}.json`);
	const bytes = await readFile(path);
	return { path, pin: JSON.parse(bytes.toString("utf8")), sha256: sha256(bytes) };
}

async function verifyProductManifest(path: string, expectedProduct: string, productPinSha: string) {
	const verified = await verifyManifest(path, expectedProduct);
	if (verified.manifest.product_pin_sha256 !== productPinSha) {
		throw new Error(`manifest was built from a different ${expectedProduct} pin: ${path}`);
	}
	return verified;
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
	if (config.kind === "repowise") {
		const productPin = await readProductPin("repowise");
		const binary = await productExecutable(join(INDEXES, "tools", "repowise-venv", "bin", "repowise"), "repowise", "REPOWISE_BIN");
		const version = run(binary, ["--version"]);
		if (!version.includes(productPin.pin.version)) throw new Error("RepoWise version differs from its product pin");
		const wheel = join(INDEXES, "tools", productPin.pin.distribution.name);
		if (await fileSha(wheel) !== productPin.pin.distribution.sha256) throw new Error("RepoWise wheel SHA-256 differs from its product pin");
		const dependencyLock = join(PRODUCT_PINS, productPin.pin.dependency_lock.name);
		if (await fileSha(dependencyLock) !== productPin.pin.dependency_lock.sha256) throw new Error("RepoWise dependency lock SHA-256 differs from its product pin");
		const verified = await verifyProductManifest(join(INDEXES, "manifests", "repowise.json"), "repowise", productPin.sha256);
		const workspace = join(INDEXES, "repowise", "workspace");
		const baseUrl = process.env.REPOWISE_URL ?? "http://127.0.0.1:7337";
		const querySurface = ["search:symbol", "search:path", "symbol:context", "workspace:blast-radius"];
		return {
			tools: [repowiseSearchTool(binary, workspace, receipts, takeId), repowiseContextTool(binary, workspace, receipts, takeId), repowiseBlastRadiusTool(baseUrl, receipts, takeId)],
			receipt: {
				mode: "real_product", product: "repowise", version: productPin.pin.version, commit: productPin.pin.commit,
				binary_sha256: productPin.pin.distribution.sha256, artifact_sha256: verified.sha256,
				manifest_sha256: verified.sha256, query_surface: querySurface, freshness: "verified",
				adapter_version: ADAPTER_VERSION,
				config_sha256: configSha({ product: "repowise", workspace: "repowise/workspace", base_url: baseUrl, query_surface: querySurface }),
				index_built_at: verified.manifest.metadata.built_at ?? null,
				index_duration_seconds: verified.manifest.metadata.duration_seconds ?? null,
				indexed_estate_sha256: snapshot.sha256 ?? null,
			},
			prompt: "Use RepoWise's search and blast-radius evidence first. Attribute only candidates present in a receipt as product_direct; verified extensions are file_search or agent_inferred.",
			receipts,
		};
	}
	if (config.kind === "codebase-memory") {
		const productPin = await readProductPin("codebase-memory");
		const binary = await productExecutable(join(INDEXES, "tools", "codebase-memory-mcp"), "codebase-memory-mcp", "CODEBASE_MEMORY_BIN");
		if (await fileSha(binary) !== productPin.pin.binary_sha256) throw new Error("Codebase Memory binary SHA-256 differs from its product pin");
		const version = run(binary, ["--version"]);
		if (!version.includes(productPin.pin.version)) throw new Error("Codebase Memory version differs from its product pin");
		const verified = await verifyProductManifest(join(INDEXES, "manifests", "codebase-memory.json"), "codebase-memory-mcp", productPin.sha256);
		const cache = join(INDEXES, "codebase-memory", "cache");
		const querySurface = ["search_graph", "query_graph:references", "trace_path:functions", "query_graph:CROSS_*", "query_graph:TESTS", "get_graph_schema"];
		return {
			tools: [codebaseMemoryTool(binary, cache, receipts, takeId)],
			receipt: {
				mode: "real_product", product: "codebase-memory-mcp", version: productPin.pin.version, commit: productPin.pin.commit,
				binary_sha256: productPin.pin.binary_sha256, artifact_sha256: verified.sha256,
				manifest_sha256: verified.sha256, query_surface: querySurface, freshness: "verified",
				adapter_version: ADAPTER_VERSION,
				config_sha256: configSha({ product: "codebase-memory-mcp", cache: "codebase-memory/cache", query_surface: querySurface }),
				index_built_at: verified.manifest.metadata.built_at ?? null,
				index_duration_seconds: verified.manifest.metadata.duration_seconds ?? null,
				indexed_estate_sha256: snapshot.sha256 ?? null,
			},
			prompt: "Use Codebase Memory's structural, traversal, and CROSS_* evidence first. Attribute only rows present in a receipt as product_direct; verified extensions are file_search or agent_inferred.",
			receipts,
		};
	}
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
