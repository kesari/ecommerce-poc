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
import { createRestrictedReadOnlyTools, isolate } from "./estate.ts";
import { buildPrompt, FIXED_THINKING, FIXED_TOOLS, parseArgs, withTimeout } from "./run.ts";
import { validateFile } from "./scoring.ts";

test("parseArgs accepts repeated records and positive numeric values", () => {
	const args = parseArgs(["--record", "REST-001", "--record", "REST-002", "--runs", "2", "--timeout", "30"]);
	assert.deepEqual(args.record, ["REST-001", "REST-002"]);
	assert.equal(args.runs, 2);
	assert.equal(args.timeout, 30);
});

for (const argv of [
	["--record", "REST-001", "--runs", "0"],
	["--record", "REST-001", "--runs", "nope"],
	["--record", "REST-001", "--timeout", "-1"],
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

test("validateFile rejects a schema-invalid answer before scoring", async () => {
	const root = await mkdtemp(join(tmpdir(), "harness-schema-test-"));
	try {
		const answer = join(root, "answer.json");
		await writeFile(answer, JSON.stringify({
			change_id: "REST-001",
			contestant: "agent-only",
			findings: { contracts: [{ type: "asyncapi", identifier: "invalid" }] },
		}));
		assert.throws(() => validateFile("answer", answer), /schema validation failed/);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});
