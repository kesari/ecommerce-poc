import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { cp, mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
	createAgentSession,
	DefaultResourceLoader,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";
import { extractAnswer } from "./answer.ts";
import { countToolCalls, createRestrictedReadOnlyTools, DEFAULT_ESTATE, isolate } from "./estate.ts";
import { buildConceptIndex, buildContractGraph, buildSymbolIndex, indexPromptSection, parseJavaSymbols } from "./indexes.ts";
import { buildPrompt, FIXED_THINKING, FIXED_TOOLS, parseArgs, productEligible, productSummary, withTimeout } from "./run.ts";
import { createRealProduct } from "./products.ts";
import { verifyHeads } from "./products.ts";
import { validateFile } from "./scoring.ts";

test("parseArgs accepts repeated records and positive numeric values", () => {
	const args = parseArgs(["--record", "REST-001", "--record", "REST-002", "--runs", "2", "--timeout", "30"]);
	assert.deepEqual(args.record, ["REST-001", "REST-002"]);
	assert.equal(args.runs, 2);
	assert.equal(args.timeout, 30);
	assert.equal(args.minToolCalls, 10);
});

test("parseArgs accepts --min-tool-calls and --contestant", () => {
	const args = parseArgs(["--record", "REST-001", "--contestant", "pi-scip", "--min-tool-calls", "4"]);
	assert.deepEqual(args.contestant, ["pi-scip"]);
	assert.equal(args.minToolCalls, 4);
	assert.equal(args.minTokens, 8000);
});

test("parseArgs accepts --min-tokens", () => {
	const args = parseArgs(["--record", "REST-001", "--min-tokens", "100"]);
	assert.equal(args.minTokens, 100);
});

for (const argv of [
	["--record", "REST-001", "--runs", "0"],
	["--record", "REST-001", "--runs", "nope"],
	["--record", "REST-001", "--timeout", "-1"],
	["--record", "REST-001", "--min-tool-calls", "0"],
	["--record", "REST-001", "--min-tokens", "0"],
	["--unknown", "value", "--record", "REST-001"],
]) {
	test(`parseArgs rejects ${argv.join(" ")}`, () => {
		assert.throws(() => parseArgs(argv));
	});
}

test("buildPrompt replaces every record placeholder", () => {
	const result = buildPrompt(
		{ change_id: "REST-001", query: "What breaks?", proposed_change: { repo: "account-service", diff: "-a\n+b" } },
		"{{CHANGE_ID}} {{QUERY}} {{CHANGE_REPO}} {{CHANGE_DIFF}}",
	);
	assert.equal(result, "REST-001 What breaks? account-service -a\n+b");
});

test("extractAnswer accepts prose-wrapped JSON and chooses the complete answer", () => {
	const answer = extractAnswer('draft {"x":1} final {"findings":{"repositories":[]}}');
	assert.deepEqual(answer, { findings: { repositories: [] } });
});

test("isolate copies only allowlisted repositories and removes generated directories", async () => {
	const root = await mkdtemp(join(tmpdir(), "harness-estate-test-"));
	try {
		const estate = join(root, "source");
		const scratch = join(root, "scratch");
		await mkdir(join(estate, "account-service", "target"), { recursive: true });
		await mkdir(join(estate, "order-service"), { recursive: true });
		await mkdir(join(estate, "ecommerce-poc", "impact-study", "harness", "records"), { recursive: true });
		await mkdir(scratch);
		await writeFile(join(estate, "account-service", "source.txt"), "source");
		await writeFile(join(estate, "account-service", "target", "generated.txt"), "generated");
		await writeFile(join(estate, "order-service", "source.txt"), "source");
		await writeFile(join(estate, "ecommerce-poc", "impact-study", "harness", "records", "answer.json"), "{}");
		const copy = await isolate(estate, scratch, ["account-service", "order-service"]);
		assert.equal(await readFile(join(copy, "account-service", "source.txt"), "utf8"), "source");
		await assert.rejects(readFile(join(copy, "account-service", "target", "generated.txt")));
		await assert.rejects(readFile(join(copy, "ecommerce-poc", "impact-study", "harness", "records", "answer.json")));
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test("restricted PI tools work inside the estate and reject outside paths", async () => {
	const root = await mkdtemp(join(tmpdir(), "harness-tool-test-"));
	try {
		const estate = join(root, "estate");
		await mkdir(estate);
		const insideFile = join(estate, "inside.txt");
		const outsideFile = join(root, "answer.json");
		await writeFile(insideFile, "inside marker");
		await writeFile(outsideFile, "answer key");
		const tools = await createRestrictedReadOnlyTools(estate);
		const read = tools.find((tool) => tool.name === "read");
		const grep = tools.find((tool) => tool.name === "grep");
		const find = tools.find((tool) => tool.name === "find");
		const ls = tools.find((tool) => tool.name === "ls");
		assert.ok(read && grep && find && ls);
		assert.match(JSON.stringify(await read.execute("inside", { path: insideFile })), /inside marker/);
		assert.match(JSON.stringify(await grep.execute("inside", { path: estate, pattern: "marker" })), /inside\.txt/);
		assert.match(JSON.stringify(await find.execute("inside", { path: estate, pattern: "*.txt" })), /inside\.txt/);
		assert.match(JSON.stringify(await ls.execute("inside", { path: estate })), /inside\.txt/);
		await assert.rejects(read.execute("outside", { path: outsideFile }), /outside isolated estate/);
		await assert.rejects(grep.execute("outside", { path: root, pattern: "answer" }), /Path not found/);
		await assert.rejects(find.execute("outside", { path: root, pattern: "*.json" }), /Path not found/);
		await assert.rejects(ls.execute("outside", { path: root }), /Path not found/);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

// The guard only protects a run if the session hands the model the restricted
// tools instead of PI's unrestricted built-ins of the same name.
test("a session serves the restricted tools, not PI's built-ins", async (context) => {
	const root = await mkdtemp(join(tmpdir(), "harness-session-test-"));
	try {
		const modelRuntime = await ModelRuntime.create({
			authPath: join(root, "auth.json"),
			modelsPath: null,
			modelsStorePath: join(root, "models-store.json"),
			refreshOnCreate: false,
		});
		const model = modelRuntime.getModels()[0];
		if (!model) return context.skip("PI has no built-in model definitions");
		const estate = join(root, "estate");
		await mkdir(estate);
		await writeFile(join(estate, "inside.txt"), "inside marker");
		await writeFile(join(root, "answer.json"), "answer key");
		const { session } = await createAgentSession({
			cwd: estate,
			model,
			thinkingLevel: FIXED_THINKING,
			tools: [...FIXED_TOOLS],
			customTools: (await createRestrictedReadOnlyTools(estate)) as any,
			modelRuntime,
			sessionManager: SessionManager.inMemory(estate),
			settingsManager: SettingsManager.inMemory({}),
			resourceLoader: new DefaultResourceLoader({
				cwd: estate,
				agentDir: root,
				noExtensions: true,
				noSkills: true,
				noPromptTemplates: true,
				noThemes: true,
				noContextFiles: true,
			}),
		});
		try {
			const active = (session as any).agent.state.tools;
			assert.deepEqual(active.map((tool: any) => tool.name).sort(), [...FIXED_TOOLS].sort());
			const read = active.find((tool: any) => tool.name === "read");
			await assert.rejects(read.execute("outside", { path: join(root, "answer.json") }), /outside isolated estate/);
			assert.match(JSON.stringify(await read.execute("inside", { path: join(estate, "inside.txt") })), /inside marker/);
		} finally {
			session.dispose();
		}
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

// A product tool the allowlist omits never reaches the agent: PI filters
// custom tools against the tools list, so the run must name every tool.
test("a session exposes product tools only when allowlisted", async (context) => {
	const root = await mkdtemp(join(tmpdir(), "harness-product-tool-test-"));
	try {
		const modelRuntime = await ModelRuntime.create({
			authPath: join(root, "auth.json"),
			modelsPath: null,
			modelsStorePath: join(root, "models-store.json"),
			refreshOnCreate: false,
		});
		const model = modelRuntime.getModels()[0];
		if (!model) return context.skip("PI has no built-in model definitions");
		const estate = join(root, "estate");
		await mkdir(estate);
		const fakeProductTool: any = {
			name: "scip_search",
			label: "SCIP search",
			description: "Stand-in for the real product tool.",
			parameters: { type: "object", properties: {}, required: [] },
			execute: async () => ({ content: [{ type: "text", text: "ok" }], details: {} }),
		};
		const base = {
			cwd: estate,
			model,
			thinkingLevel: FIXED_THINKING,
			customTools: [fakeProductTool],
			modelRuntime,
			sessionManager: SessionManager.inMemory(estate),
			settingsManager: SettingsManager.inMemory({}),
			resourceLoader: new DefaultResourceLoader({
				cwd: estate,
				agentDir: root,
				noExtensions: true,
				noSkills: true,
				noPromptTemplates: true,
				noThemes: true,
				noContextFiles: true,
			}),
		} as any;
		const visibleNames = async (tools: string[]) => {
			const { session } = await createAgentSession({ ...base, tools });
			try {
				return (session as any).agent.state.tools.map((tool: any) => tool.name).sort();
			} finally {
				session.dispose();
			}
		};
		assert.deepEqual(await visibleNames([...FIXED_TOOLS]), [...FIXED_TOOLS].sort());
		assert.deepEqual(
			await visibleNames([...FIXED_TOOLS, "scip_search"]),
			[...FIXED_TOOLS, "scip_search"].sort(),
		);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test("productSummary counts attempts, successes, and failures", () => {
	assert.deepEqual(productSummary(undefined), null);
	assert.deepEqual(productSummary([]), { attempted: 0, succeeded: 0, failed: 0 });
	assert.deepEqual(
		productSummary([{ success: true }, { success: false }]),
		{ attempted: 2, succeeded: 1, failed: 1 },
	);
});

test("productEligible is null off-product and success-gated on-product", () => {
	const agent = { label: "agent-only", provider: "x", model: "y" } as any;
	const real = { label: "scip", provider: "x", model: "y", product: { kind: "scip" } } as any;
	assert.equal(productEligible(agent, []), null);
	assert.equal(productEligible(agent, undefined), null);
	assert.equal(productEligible(real, []), false);
	assert.equal(productEligible(real, [{ success: false }]), false);
	assert.equal(productEligible(real, [{ success: true }]), true);
});

test("withTimeout rejects at the deadline and invokes cancellation", async () => {	let cancelled = false;
	await assert.rejects(
		withTimeout(new Promise(() => undefined), 0.01, () => { cancelled = true; }),
		/timed out/,
	);
	assert.equal(cancelled, true);
});

test("validateFile rejects a structurally broken answer but tolerates one bad finding", async () => {
	const root = await mkdtemp(join(tmpdir(), "harness-schema-test-"));
	const write = async (name: string, body: unknown) => {
		const path = join(root, name);
		await writeFile(path, JSON.stringify(body));
		return path;
	};
	try {
		const broken = await write("broken.json", {
			change_id: "REST-001",
			contestant: "not-a-contestant",
			findings: {},
		});
		assert.throws(() => validateFile("answer", broken), /schema validation failed/);

		// One unusable contract must not void the run; the scorer drops and charges for it.
		const usable = await write("usable.json", {
			change_id: "REST-001",
			contestant: "agent-only",
			findings: { contracts: [{ type: "carrier-pigeon", identifier: "invalid" }] },
		});
		assert.doesNotThrow(() => validateFile("answer", usable));
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test("isolate refuses to copy a ground-truth leak", async () => {
	const root = await mkdtemp(join(tmpdir(), "harness-leak-test-"));
	try {
		const estate = join(root, "source");
		const scratch = join(root, "scratch");
		await mkdir(join(estate, "account-service"), { recursive: true });
		await mkdir(scratch);
		await writeFile(join(estate, "account-service", "REST-001.json"), "{}");
		await assert.rejects(isolate(estate, scratch, ["account-service"]), /ground-truth leak/);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test("parseJavaSymbols maps declarations to FQNs", () => {
	const entries = parseJavaSymbols(
		"package com.poc.account.api.dto;\n\npublic record AddressResponse(\n    String postalCode) {}\n",
		"account-service",
		"account-service/AddressResponse.java",
	);
	assert.deepEqual(entries, [{
		fqn: "com.poc.account.api.dto.AddressResponse",
		repo: "account-service",
		file: "account-service/AddressResponse.java",
		line: 3,
		kind: "record",
	}]);
});

test("index builders stay deterministic on a fixture estate", async () => {
	const root = await mkdtemp(join(tmpdir(), "harness-index-test-"));
	try {
		const workdir = join(root, "estate");
		await mkdir(join(workdir, "order-service", "src", "main", "java", "com", "poc"), { recursive: true });
		await mkdir(join(workdir, "order-service", "asyncapi", "schemas"), { recursive: true });
		await mkdir(join(workdir, "order-service", "openapi"), { recursive: true });
		await mkdir(join(workdir, "shipment-service", "asyncapi"), { recursive: true });
		await writeFile(
			join(workdir, "order-service", "src", "main", "java", "com", "poc", "Saga.java"),
			"package com.poc.order;\n\npublic class SagaOrchestrator {}\n",
		);
		await writeFile(join(workdir, "order-service", "asyncapi", "schemas", "order.confirmed.v1.json"), "{}\n");
		await writeFile(join(workdir, "order-service", "asyncapi", "order-service.yaml"), "channels:\n  order.confirmed.v1:\n    publish:\n      operationId: emit\n");
		await writeFile(join(workdir, "shipment-service", "asyncapi", "shipment-service.yaml"), "channels:\n  order.confirmed.v1:\n    subscribe:\n      operationId: consume\n");
		await writeFile(join(workdir, "order-service", "openapi", "order-service.yaml"), "paths:\n  /api/v1/orders:\n    post: {}\n");
		const symbols = await buildSymbolIndex(workdir, ["order-service"]);
		assert.ok(symbols.entries.some((entry) => entry.fqn === "com.poc.order.SagaOrchestrator"));
		assert.match(symbols.sha256, /^[a-f0-9]{64}$/);
		const contracts = await buildContractGraph(workdir, ["order-service", "shipment-service"]);
		const topic = contracts.topics.find((entry) => entry.topic === "order.confirmed.v1");
		assert.ok(topic);
		assert.deepEqual(topic.producers, ["order-service"]);
		assert.deepEqual(topic.consumers, ["shipment-service"]);
		assert.ok(contracts.endpoints.some((endpoint) => endpoint.path === "/api/v1/orders"));
		assert.match(contracts.sha256, /^[a-f0-9]{64}$/);
		const again = await buildSymbolIndex(workdir, ["order-service"]);
		assert.equal(again.sha256, symbols.sha256);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test("indexPromptSection is empty for index-free contestants", () => {
	assert.equal(indexPromptSection([]), "");
	assert.match(indexPromptSection(["symbol"]), /symbols\.json/);
	assert.match(indexPromptSection(["contract"]), /contracts\.json/);
	assert.match(indexPromptSection(["concept"]), /concepts\.json/);
	assert.match(indexPromptSection(["symbol", "contract"]), /symbols\.json.*contracts\.json/);
});

test("buildConceptIndex extracts configured calls and owned tables", async () => {
	const root = await mkdtemp(join(tmpdir(), "harness-concept-test-"));
	try {
		const workdir = join(root, "estate");
		await mkdir(join(workdir, "order-service", "src", "main", "resources"), { recursive: true });
		await mkdir(join(workdir, "order-service", "src", "main", "resources", "db", "migration"), { recursive: true });
		await writeFile(
			join(workdir, "order-service", "src", "main", "resources", "application.yml"),
			"clients:\n  basket:\n    base-url: ${BASKET_BASE_URL:http://localhost:8083}\n",
		);
		await writeFile(
			join(workdir, "order-service", "src", "main", "resources", "db", "migration", "V1__order.sql"),
			"CREATE TABLE orders (id uuid PRIMARY KEY);\n",
		);
		const concept = await buildConceptIndex(workdir, ["order-service"]);
		assert.deepEqual(concept.calls, [{
			from: "order-service",
			to: "basket-service",
			evidence: "order-service/src/main/resources/application.yml:3",
		}]);
		assert.deepEqual(concept.tables, [{
			repo: "order-service",
			table: "orders",
			evidence: "order-service/src/main/resources/db/migration/V1__order.sql",
		}]);
		assert.match(concept.sha256, /^[a-f0-9]{64}$/);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test("countToolCalls counts every execute", async () => {
	const calls: string[] = [];
	const tools: any[] = [
		{ name: "read", execute: async (...args: any[]) => { calls.push(args[0]); return "ok"; } },
		{ name: "ls" },
	];
	const getCount = countToolCalls(tools);
	await tools[0].execute("a", { path: "x" });
	await tools[0].execute("b", { path: "y" });
	assert.equal(getCount(), 2);
	assert.deepEqual(calls, ["a", "b"]);
});

test("countToolCalls records per-tool success and failure telemetry", async () => {
	const tools: any[] = [
		{ name: "read", execute: async () => "ok" },
		{ name: "grep", execute: async () => { throw new Error("boom"); } },
	];
	const getCount = countToolCalls(tools);
	await tools[0].execute("a", {});
	await assert.rejects(tools[1].execute("b", {}), /boom/);
	assert.equal(getCount(), 2);
	const telemetry = (getCount as any).telemetry();
	assert.deepEqual(telemetry.byName.read, { calls: 1, succeeded: 1, failed: 0 });
	assert.deepEqual(telemetry.byName.grep, { calls: 1, succeeded: 0, failed: 1 });
	assert.equal(telemetry.total, 2);
	assert.equal(telemetry.succeeded, 1);
	assert.equal(telemetry.failed, 1);
});

test("real SCIP integration verifies pins and invokes scip-search", async () => {
	const pins = JSON.parse(await readFile(join(import.meta.dirname, "..", "indexes", "pins.json"), "utf8"));
	const snapshot = {
		sha256: "0".repeat(64),
		repositories: Object.keys(pins.repositories),
		revisions: Object.entries(pins.repositories).map(([name, value]: [string, any]) => ({
			name,
			commit: value.commit,
			dirty: value.dirty,
		})),
	};
	const integration = await createRealProduct({ kind: "scip" }, "unused", snapshot);
	assert.equal(integration.receipt.mode, "real_product");
	assert.equal(integration.receipt.product, "scip-java+scip-search");
	const output = await integration.tools[0].execute("test", { operation: "symbols", name: "AddressResponse" });
	assert.match(output.content[0].text, /AddressResponse/);
});

test("real product invocations leave runner-generated receipts", async () => {
	const pins = JSON.parse(await readFile(join(import.meta.dirname, "..", "indexes", "pins.json"), "utf8"));
	const snapshot = {
		sha256: "0".repeat(64),
		repositories: Object.keys(pins.repositories),
		revisions: Object.entries(pins.repositories).map(([name, value]: [string, any]) => ({
			name,
			commit: value.commit,
			dirty: value.dirty,
		})),
	};
	const integration = await createRealProduct({ kind: "scip" }, "unused", snapshot);
	assert.deepEqual(integration.receipts, []);
	await integration.tools[0].execute("test", { operation: "symbols", name: "AddressResponse" });
	assert.equal(integration.receipts.length, 1);
	const receipt = integration.receipts[0];
	assert.equal(receipt.tool, "scip_search");
	assert.equal(receipt.operation, "symbols");
	assert.deepEqual(receipt.parameters, { operation: "symbols", name: "AddressResponse" });
	assert.equal(receipt.success, true);
	assert.match(receipt.output_sha256, /^[a-f0-9]{64}$/);
	assert.equal(typeof receipt.duration_ms, "number");
	assert.match(receipt.id, /^r\d+$/);
});

test("real product refuses a snapshot that does not match pins", async () => {
	const pins = JSON.parse(await readFile(join(import.meta.dirname, "..", "indexes", "pins.json"), "utf8"));
	const names = Object.keys(pins.repositories);
	const snapshot = {
		sha256: "0".repeat(64),
		repositories: names,
		revisions: names.map((name, index) => ({
			name,
			commit: index === 0 ? "f".repeat(40) : pins.repositories[name].commit,
			dirty: false,
		})),
	};
	await assert.rejects(createRealProduct({ kind: "scip" }, "unused", snapshot), /freshness check failed/);
});

test("verifyHeads passes on the pinned estate and fails on drift", async (context) => {
	const pins = JSON.parse(await readFile(join(import.meta.dirname, "..", "indexes", "pins.json"), "utf8"));
	const probe = spawnSync("git", ["-C", join(DEFAULT_ESTATE, "account-service"), "rev-parse", "HEAD"], { encoding: "utf8" });
	if (probe.status !== 0) return context.skip("estate repositories are not git checkouts here");
	const estate = DEFAULT_ESTATE;
	assert.doesNotThrow(() => verifyHeads(estate, pins));
	const drifted = JSON.parse(JSON.stringify(pins));
	drifted.repositories["account-service"].commit = "0".repeat(40);
	assert.throws(() => verifyHeads(estate, drifted), /drifted mid-run/);
});

function shell(binary: string, args: string[], cwd: string) {
	const result = spawnSync(binary, args, { cwd, encoding: "utf8", maxBuffer: 4 * 1024 * 1024 });
	if (result.error) throw result.error;
	if (result.status !== 0) throw new Error(`${binary} ${args[0]} failed: ${(result.stderr || result.stdout).slice(0, 500)}`);
	return `${result.stdout ?? ""}`;
}

function which(binary: string) {
	const found = spawnSync("which", [binary], { encoding: "utf8" });
	if (found.status !== 0 || !found.stdout.trim()) throw new Error(`${binary} is not installed; skipping test`);
	return found.stdout.trim();
}

test("scip freshness: touch, stale query, re-index, fresh query", async (context) => {
	let cs: string;
	try {
		cs = which("cs");
		which("scip-search");
	} catch {
		return context.skip("scip toolchain not installed");
	}
	const root = await mkdtemp(join(tmpdir(), "harness-fresh-scip-"));
	try {
		const repo = join(root, "account-service");
		await cp(join(import.meta.dirname, "..", "..", "..", "POC-order-microservices", "account-service"), repo, { recursive: true });
		const index = join(root, "index.scip");
		shell(cs, ["launch", "com.sourcegraph:scip-java_2.13:0.10.4", "--", "index"], repo);
		await cp(join(repo, "index.scip"), index);
		const query = () => shell("scip-search", ["symbols", "--index", index, "--name", "FreshnessProbe"], root);
		assert.doesNotMatch(query(), /FreshnessProbe/);
		await writeFile(
			join(repo, "src", "main", "java", "com", "poc", "account", "api", "dto", "FreshnessProbe.java"),
			"package com.poc.account.api.dto;\n\npublic record FreshnessProbe(String marker) {}\n",
		);
		assert.doesNotMatch(query(), /FreshnessProbe/);
		const rebuildStarted = performance.now();
		shell(cs, ["launch", "com.sourcegraph:scip-java_2.13:0.10.4", "--", "index"], repo);
		await cp(join(repo, "index.scip"), index);
		console.info(`scip re-index latency ms: ${Math.round(performance.now() - rebuildStarted)}`);
		assert.match(query(), /FreshnessProbe/);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test("graphify freshness: touch, stale query, re-extract, fresh query", async (context) => {
	let graphify: string;
	try {
		graphify = which("graphify");
	} catch {
		return context.skip("graphify is not installed");
	}
	const root = await mkdtemp(join(tmpdir(), "harness-fresh-graphify-"));
	try {
		const repo = join(root, "account-service");
		await cp(join(import.meta.dirname, "..", "..", "..", "POC-order-microservices", "account-service"), repo, { recursive: true });
		const graph = join(root, "graph.json");
		shell(graphify, ["update", repo, "--no-cluster"], root);
		await cp(join(repo, "graphify-out", "graph.json"), graph);
		const query = () => shell(graphify, ["query", "FreshnessProbe", "--graph", graph], root);
		assert.doesNotMatch(query(), /FreshnessProbe/);
		await writeFile(
			join(repo, "src", "main", "java", "com", "poc", "account", "api", "dto", "FreshnessProbe.java"),
			"package com.poc.account.api.dto;\n\npublic record FreshnessProbe(String marker) {}\n",
		);
		assert.doesNotMatch(query(), /FreshnessProbe/);
		const rebuildStarted = performance.now();
		shell(graphify, ["update", repo, "--no-cluster"], root);
		await cp(join(repo, "graphify-out", "graph.json"), graph);
		console.info(`graphify re-extract latency ms: ${Math.round(performance.now() - rebuildStarted)}`);
		assert.match(query(), /FreshnessProbe/);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

test("gortex freshness: daemon reports pinned HEADs as fresh", async (context) => {
	let gortex: string;
	try {
		gortex = which("gortex");
	} catch {
		return context.skip("gortex is not installed");
	}
	let status: string;
	try {
		status = shell(gortex, ["repos"], import.meta.dirname);
	} catch {
		return context.skip("gortex daemon unreachable");
	}
	const pins = JSON.parse(await readFile(join(import.meta.dirname, "..", "indexes", "pins.json"), "utf8"));
	for (const [name, value] of Object.entries(pins.repositories) as [string, any][]) {
		assert.match(status, new RegExp(`${name}.*${value.commit.slice(0, 12)}`), `${name} not indexed at its pin`);
	}
});
