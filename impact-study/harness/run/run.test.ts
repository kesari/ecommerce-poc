import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
	createAgentSession,
	DefaultResourceLoader,
	getAgentDir,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";
import { extractAnswer } from "./answer.ts";
import { countToolCalls, createRestrictedReadOnlyTools, isolate } from "./estate.ts";
import { buildContractGraph, buildSymbolIndex, indexPromptSection, parseJavaSymbols } from "./indexes.ts";
import { buildPrompt, FIXED_THINKING, FIXED_TOOLS, parseArgs, withTimeout } from "./run.ts";
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
	const modelRuntime = await ModelRuntime.create();
	const model = (await modelRuntime.getAvailable())[0];
	if (!model) return context.skip("no provider credentials configured");
	const root = await mkdtemp(join(tmpdir(), "harness-session-test-"));
	try {
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
				agentDir: getAgentDir(),
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

test("withTimeout rejects at the deadline and invokes cancellation", async () => {
	let cancelled = false;
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
