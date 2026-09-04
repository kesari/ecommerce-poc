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

/** Bump when this adapter's query construction or normalization changes. */
const ADAPTER_VERSION = "1.0.0";

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

function assertRepo(value: unknown) {
	if (typeof value !== "string" || !(ESTATE_REPOSITORIES as readonly string[]).includes(value)) {
		throw new Error("repo must be one of the pinned estate repositories");
	}
	return value;
}

function scipTool(binary: string, index: string) {
	const operations = ["symbols", "references", "implementations", "graph", "callers", "callees", "impact"];
	return {
		name: "scip_search",
		label: "SCIP search",
		description: "Query the real aggregated scip-java index through scip-search. Results are product-direct evidence; cross-service connections absent from the output are not.",
		parameters: schema({
			operation: stringEnum(operations),
			name: textParameter("Literal partial symbol name."),
		}, ["operation", "name"]),
		execute: async (_id: string, params: any) => {
			const operation = assertQuery(params.operation, "operation");
			if (!operations.includes(operation)) throw new Error("unsupported SCIP operation");
			const name = assertQuery(params.name, "name");
			return result(run(binary, [operation, "--index", index, "--name", name, "--json"]));
		},
	};
}

function graphifyTool(binary: string, graph: string) {
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
			return result(run(binary, args));
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

function gortexTool(binary: string, estate: string, pins: any) {
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
			return result(run(binary, ["query", operation, query, "--format", "json", "--limit", "100", "--index", join(estate, repo)]));
		},
	};
}

function gortexContractsTool(binary: string, estate: string, pins: any) {
	const actions = ["list", "check", "validate", "bridge_rank", "bridge_impact", "api_impact"];
	return {
		name: "gortex_contracts",
		label: "Gortex contracts",
		description: "Use Gortex's native read-only contract bridge or fused API-impact analysis across the pinned workspace.",
		parameters: schema({
			action: stringEnum(actions),
			query: { type: "string", maxLength: 300, description: "Route, bridge query, or graph symbol, depending on action." },
			repo: stringEnum([...ESTATE_REPOSITORIES], "Repository used as the query view."),
		}, ["action", "repo"]),
		execute: async (_id: string, params: any) => {
			verifyHeads(estate, pins);
			const action = assertQuery(params.action, "action");
			if (!actions.includes(action)) throw new Error("unsupported Gortex contract operation");
			const repo = assertRepo(params.repo);
			const indexArgs = ["--index", join(estate, repo), "--format", "json"];
			if (action === "api_impact") {
				const query = assertQuery(params.query, "query");
				return result(run(binary, ["call", "api_impact", "--arg", `route=${query}`, "--arg", `repo=${repo}`, ...indexArgs]));
			}
			const contractAction = action.startsWith("bridge_") ? "bridge" : action;
			const args = ["call", "contracts", "--arg", `action=${contractAction}`];
			if (action === "bridge_rank") {
				args.push("--arg", "mode=rank", "--arg", `query=${assertQuery(params.query, "query")}`);
			} else if (action === "bridge_impact") {
				args.push("--arg", "mode=impact", "--arg", `symbol=${assertQuery(params.query, "query")}`);
			} else {
				args.push("--arg", `repo=${repo}`);
			}
			return result(run(binary, [...args, ...indexArgs]));
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
): Promise<{ tools: any[]; receipt: ProductReceipt; prompt: string }> {
	const pinsBytes = await readFile(PINS);
	const pins = JSON.parse(pinsBytes.toString("utf8"));
	verifyEstate(snapshot, pins);
	if (config.kind === "scip") {
		const binary = executable("scip-search", "SCIP_SEARCH_BIN");
		if (await fileSha(binary) !== pins.toolchain["scip-search-sha256"]) throw new Error("scip-search binary SHA-256 differs from pins");
		const path = join(INDEXES, "manifests", "scip.json");
		const verified = await verifyManifest(path, "scip-java+scip-search");
		const index = join(INDEXES, "scip", "estate.scip");
		return {
			tools: [scipTool(binary, index)],
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
			tools: [graphifyTool(binary, graph)],
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
		};
	}
	const binary = executable("gortex", "GORTEX_BIN");
	if (await fileSha(binary) !== pins.toolchain["gortex-sha256"]) throw new Error("Gortex binary SHA-256 differs from pins");
	const admin = join(INDEXES, "index_admin.py");
	run("python3", [admin, "verify-gortex", "--estate", estate, "--binary", binary, "--require-daemon"]);
	const artifactSha = sha256(JSON.stringify({ repositories: pins.repositories, workspace: pins.toolchain["gortex-workspace"] }));
	return {
		tools: [gortexTool(binary, estate, pins), gortexContractsTool(binary, estate, pins)],
		receipt: {
			mode: "real_product", product: "gortex", version: pins.toolchain.gortex,
			commit: pins.toolchain["gortex-commit"], binary_sha256: pins.toolchain["gortex-sha256"],
			artifact_sha256: artifactSha, manifest_sha256: null,
			query_surface: ["symbol", "usages", "callers", "calls", "dependents", "deps", "implementations", "contracts", "api_impact"], freshness: "verified",
			adapter_version: ADAPTER_VERSION,
			config_sha256: configSha({ product: "gortex", workspace: pins.toolchain["gortex-workspace"], query_surface: ["symbol", "usages", "callers", "calls", "dependents", "deps", "implementations", "contracts", "api_impact"] }),
			index_built_at: null,
			index_duration_seconds: null,
			indexed_estate_sha256: snapshot.sha256 ?? null,
		},
		prompt: "Use gortex_query for graph facts. Attribute only returned nodes and edges as product_direct; conclusions you derive are agent_inferred.",
	};
}
