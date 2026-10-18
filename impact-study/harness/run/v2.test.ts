import assert from "node:assert/strict";
import test from "node:test";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { preflightV2, scoreAnswer, validateFile } from "./scoring.ts";
import { assertEstateSnapshot, captureEstateSnapshot, isolate } from "./estate.ts";
import { persistRunReceipts } from "./run.ts";
import { createHash } from "node:crypto";
import { HARNESS } from "./paths.ts";

const budgetPolicy = { timeout_seconds: 1800, max_tokens: 1000000, max_cost_usd: 2,
	cost_enforcement: "catalog_context_reservation", token_enforcement: "context_window_reservation",
	retries: false, compaction: false } as const;
const usage = { tokens_consumed: 25, cost_usd: 0.01, elapsed_seconds: 2 };

function record() {
	return {
		protocol_version: 2, change_id: "REST-BOUNDARY", status: "draft", category: "rest_contract",
		query: "What downstream binding must change?", origin_repo: "account",
		snapshot_sha256: "1".repeat(64), proposed_change: { repo: "account", diff: "- postalCode\n+ postcode" },
		repositories: [{ repo: "account", runtime_status: "unchanged" }, { repo: "order", runtime_status: "broken" }],
		symbols: [], edges: [], repair_alternatives: [{ name: "consumer-read", actions: [{
			repo: "order", target: "AccountClient.AddressResponse.postalCode", operation: "read_json_field",
			before: "postalCode", after: "postcode", critical: true,
		}], obligations: [{ repo: "account", obligation: "validation_only" }, { repo: "order", obligation: "production_edit" }] }],
	};
}

test("v2 schema dispatch validates records and structured answers without calling a provider", async () => {
	const root = await mkdtemp(join(tmpdir(), "v2-dispatch-"));
	try {
		const recordPath = join(root, "record.json");
		const answerPath = join(root, "answer.json");
		await writeFile(recordPath, JSON.stringify(record()));
		validateFile("record", recordPath);
		const answer = { protocol_version: 2, change_id: "REST-BOUNDARY", snapshot_sha256: "1".repeat(64), findings: {
			repositories: [], obligations: [], symbols: [], edges: [], actions: [],
		} };
		await writeFile(answerPath, JSON.stringify(answer));
		validateFile("answer", answerPath);
		await writeFile(answerPath, JSON.stringify({ ...answer, findings: { ...answer.findings, actions: [{ repo: "order" }] } }));
		assert.throws(() => validateFile("answer", answerPath), /schema validation failed/);
		await writeFile(recordPath, JSON.stringify({ ...record(), protocol_version: 99 }));
		assert.throws(() => validateFile("record", recordPath), /unsupported protocol/);
	} finally { await rm(root, { recursive: true, force: true }); }
});

test("v2 preflight refuses untrusted input and scoring cannot fall back to v1", async () => {
	const root = await mkdtemp(join(tmpdir(), "v2-untrusted-"));
	try {
		const recordPath = join(root, "record.json");
		const answerPath = join(root, "answer.json");
		const bundlePath = join(root, "oracle.json");
		await writeFile(recordPath, JSON.stringify(record()));
		await writeFile(answerPath, JSON.stringify({ protocol_version: 2 }));
		await writeFile(bundlePath, "{}");
		assert.throws(() => preflightV2(recordPath, {
			oracleBundle: bundlePath, trustedOracleSha256: "0".repeat(64), artifactRoot: root,
		}), /oracle preflight failed/);
		await assert.rejects(scoreAnswer(answerPath, recordPath), /independent oracle bundle/);
		await writeFile(answerPath, JSON.stringify({ change_id: "REST-BOUNDARY", findings: {} }));
		await assert.rejects(scoreAnswer(answerPath, recordPath), /v2 record requires a v2 answer/);
	} finally { await rm(root, { recursive: true, force: true }); }
});

test("estate changes after capture cannot reach a later attempt with stale provenance", async () => {
	const root = await mkdtemp(join(tmpdir(), "v2-estate-drift-"));
	try {
		const source = join(root, "source");
		const scratch = join(root, "scratch");
		await mkdir(join(source, "repo"), { recursive: true });
		await mkdir(scratch);
		await writeFile(join(source, "repo", "Source.java"), "class Source { int value = 1; }");
		const snapshot = await captureEstateSnapshot(source, ["repo"]);
		const copy = await isolate(source, scratch, ["repo"]);
		await assertEstateSnapshot(copy, snapshot, true);
		await writeFile(join(source, "repo", "Source.java"), "class Source { int value = 999; }");
		await assert.rejects(assertEstateSnapshot(source, snapshot), /estate changed after snapshot/);
		await writeFile(join(copy, "repo", "Source.java"), "class Source { int value = 999; }");
		await assert.rejects(assertEstateSnapshot(copy, snapshot, true), /estate changed after snapshot/);
	} finally { await rm(root, { recursive: true, force: true }); }
});

test("trusted per-run receipts are persisted before model output and cannot be overwritten", async () => {
	const root = await mkdtemp(join(tmpdir(), "v2-receipt-persist-"));
	try {
		const saved = await persistRunReceipts(root, { change_id: "REST-001", snapshot_sha256: "1".repeat(64) },
			"pi-codex", "2026-10-16T00:00:00.000Z", { prompt: "public task", estateSha256: "2".repeat(64), budgetPolicy, usage });
		const body = JSON.parse(await readFile(saved.path, "utf8"));
		assert.deepEqual(body.receipts, []);
		assert.equal(body.product_provenance, null);
		assert.equal(body.protocol_version, 2);
		assert.equal(body.change_id, "REST-001");
		assert.deepEqual(body.budget_policy, budgetPolicy);
		assert.deepEqual(body.usage, usage);
		assert.match(saved.sha256, /^[a-f0-9]{64}$/);
		await assert.rejects(persistRunReceipts(root, { change_id: "REST-001", snapshot_sha256: "1".repeat(64) },
			"pi-codex", "2026-10-16T00:00:00.000Z", { prompt: "changed", estateSha256: "2".repeat(64), budgetPolicy, usage }), /EEXIST/);
	} finally { await rm(root, { recursive: true, force: true }); }
});

test("runtime product citation scores through the TS bridge without entering the frozen oracle", async () => {
	const root = await mkdtemp(join(tmpdir(), "v2-live-receipt-bridge-"));
	try {
		const recordPath = join(HARNESS, "records", "v2", "REST-001.json");
		const source = JSON.parse(await readFile(recordPath, "utf8"));
		const evidenceRoot = join(HARNESS, "verification", "evidence", "REST-001");
		const pins = JSON.parse(await readFile(join(HARNESS, "verification", "rest001", "approved-pins.json"), "utf8"));
		const hash = (text: string) => createHash("sha256").update(text).digest("hex");
		const output = "native fixture output";
		const receipt = { id: "r1", tool: "fixture_query", operation: "symbol", parameters: { query: "postalCode" },
			requested_query: "postalCode", executed_query: "postalCode", success: true,
			output, output_sha256: hash(JSON.stringify({ content: [{ type: "text", text: output }] })),
			output_bytes: Buffer.byteLength(output), output_full_bytes: Buffer.byteLength(output), truncated: false,
			output_normalized: output, output_normalized_sha256: hash(output), duration_ms: 1, error: null };
		const runtime = { prompt: "public task", estateSha256: "2".repeat(64), budgetPolicy, usage,
			productReceipt: { mode: "real_product", indexed_estate_sha256: "2".repeat(64) }, productReceipts: [receipt],
			playbook: [{ step: { intention: "locate", tool: "fixture_query", params: { query: "postalCode" } }, ok: true, receipt_id: "r1" }] };
		const saved = await persistRunReceipts(root, source, "fixture-tool", "2026-10-16T00:00:00.000Z", runtime);
		const envelope = JSON.parse(await readFile(saved.path, "utf8"));
		const plan = source.repair_alternatives[0];
		const findings = { repositories: source.repositories, symbols: source.symbols,
			edges: source.edges.map((x: any) => ({ ...x, receipt_id: "r1" })), obligations: plan.obligations,
			actions: plan.actions.map(({ critical: _critical, ...action }: any) => action) };
		const answerPath = join(root, "fixture.json");
		await writeFile(answerPath, JSON.stringify({ protocol_version: 2, change_id: source.change_id,
			snapshot_sha256: source.snapshot_sha256, run_started_at: envelope.run_started_at,
			prompt_sha256: envelope.prompt_sha256, estate_sha256: envelope.estate_sha256,
			product_provenance: envelope.product_provenance, product_playbook: envelope.product_playbook,
			budget_policy: budgetPolicy, ...usage, product_tool_receipts: [receipt], findings }));
		const { report } = await scoreAnswer(answerPath, recordPath, {
			oracleBundle: join(evidenceRoot, "oracle-bundle.json"), trustedOracleSha256: pins.oracle_bundle_sha256,
			artifactRoot: evidenceRoot, runReceiptBundle: saved.path, trustedRunReceiptsSha256: saved.sha256,
		});
		assert.equal(report.benchmark_eligible, true);
		assert.equal(report.product_provenance.status, "verified");
		assert.equal(report.metrics.actions.recall, 1);
		assert.equal(report.run_receipt_bundle_sha256, saved.sha256);
	} finally { await rm(root, { recursive: true, force: true }); }
});
