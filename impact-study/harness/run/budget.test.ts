import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { AttemptBudget, AttemptError, superviseAttempt } from "./budget.ts";
import type { BudgetPolicy } from "./budget.ts";

const policy: BudgetPolicy = { max_tokens: 1_000_000, max_cost_usd: 5, timeout_seconds: 30,
	cost_enforcement: "catalog_context_reservation", token_enforcement: "context_window_reservation",
	retries: false, compaction: false };
const model = { api: "openai-codex-responses", id: "pinned-test-model", contextWindow: 272000, maxTokens: 128000,
	cost: { input: 2, output: 12, cacheRead: 0.2, cacheWrite: 2.5,
		tiers: [{ inputTokensAbove: 272000, input: 4, output: 18, cacheRead: 0.4, cacheWrite: 5 }] } };

function assistant(input: number, output: number, cost: number, stopReason = "stop") {
	return { type: "message_end", message: { role: "assistant", stopReason,
		usage: { input, output, cacheRead: 0, cacheWrite: 0, cost: { total: cost } } } };
}

test("Codex reserves full unchanged context and output at worst base/tier rates", () => {
	const budget = new AttemptBudget(policy);
	assert.deepEqual(budget.reserve(model), { maxTokens: 128000, maxRetries: 0 });
	assert.equal(model.contextWindow, 272000);
	const reservation = budget.reservations[0];
	assert.equal(reservation.output_limit, "model_catalog_maximum");
	assert.ok(Math.abs(reservation.maximum_request_cost_usd - 3.664) < 1e-12);
});

test("insufficient full-request token or cost budget prevents a Codex request", () => {
	for (const cap of [{ max_tokens: 399999 }, { max_cost_usd: 3 }]) {
		const budget = new AttemptBudget({ ...policy, ...cap });
		let requests = 0;
		assert.throws(() => { budget.reserve(model); requests++; }, (error: any) => error.status === "budget_exhausted");
		assert.equal(requests, 0);
		assert.equal(budget.exposure().unknown_usage, false);
	}
});

test("next reservation accounts for settled usage and refuses before further work", () => {
	const budget = new AttemptBudget(policy);
	budget.reserve(model);
	budget.observe(assistant(200000, 100000, 2));
	assert.equal(budget.exposure().unknown_usage, false);
	assert.throws(() => budget.reserve(model), /cannot reserve/);
	assert.throws(() => budget.check(), /cannot reserve/);
	assert.equal(budget.stats.cost, 2);
});

test("audited output limits clamp but OpenAI Responses minimum16 cannot under-reserve", () => {
	const bounded = { ...model, api: "openai-responses", contextWindow: 100, maxTokens: 100 };
	const budget = new AttemptBudget({ ...policy, max_tokens: 120 });
	assert.equal(budget.reserve(bounded).maxTokens, 20);
	const tooSmall = new AttemptBudget({ ...policy, max_tokens: 115 });
	assert.throws(() => tooSmall.reserve(bounded), /cannot reserve/);
});

test("unsupported APIs and unknown prices fail closed without a reservation", () => {
	for (const invalid of [{ ...model, api: "unknown-api" }, { ...model, cost: { input: 1 } }]) {
		const budget = new AttemptBudget(policy);
		assert.throws(() => budget.reserve(invalid), (error: any) => error.status === "setup_failed");
		assert.equal(budget.reservations.length, 0);
	}
});

test("failed provider with zero reported usage retains worst outstanding cost exposure", () => {
	const budget = new AttemptBudget(policy);
	budget.reserve(model);
	budget.observe(assistant(0, 0, 0, "error"));
	assert.equal(budget.stats.cost, 0);
	assert.equal(budget.failure?.status, "provider_failed");
	assert.equal(budget.exposure().unknown_usage, true);
	assert.ok(Math.abs(budget.exposure().exposure_upper_bound_usd - 3.664) < 1e-12);
});

test("all token categories count and over-limit provider usage stops further work", () => {
	const budget = new AttemptBudget({ ...policy, max_tokens: 1000 });
	const event = assistant(100, 200, 0.1);
	event.message.usage.cacheRead = 300;
	event.message.usage.cacheWrite = 500;
	budget.observe(event);
	assert.equal(budget.stats.tokens.total, 1100);
	assert.equal(budget.failure?.status, "budget_exhausted");
	assert.throws(() => budget.check(), /usage exceeded/);
});

test("parent deadline terminates synchronous child work and retains prior progress", async () => {
	const root = await mkdtemp(join(tmpdir(), "budget-worker-"));
	try {
		const entry = join(root, "worker.mjs");
		await writeFile(entry, `import {spawnSync} from 'node:child_process';
process.on('message', m => { if(m.type !== 'start') return;
process.send({type:'progress',value:{receipts:[{id:'r1',success:true}]}});
spawnSync(process.execPath,['-e','setInterval(()=>{},1000)']); });`);
		const progress: any[] = [];
		const started = performance.now();
		await assert.rejects(superviseAttempt(entry, {}, { ...policy, timeout_seconds: 0.2 }, (value) => progress.push(value)),
			(error: any) => error.status === "budget_exhausted");
		assert.ok(performance.now() - started < 2000);
		assert.deepEqual(progress[0].receipts, [{ id: "r1", success: true }]);
	} finally { await rm(root, { recursive: true, force: true }); }
});

test("parent records reservation before ACK permits simulated provider work", async () => {
	const root = await mkdtemp(join(tmpdir(), "budget-reservation-"));
	try {
		const entry = join(root, "worker.mjs");
		const marker = join(root, "provider-started");
		await writeFile(entry, `import {writeFileSync} from 'node:fs';
process.on('message',m=>{ if(m.type==='start') process.send({type:'reservation',id:1,value:{maximum:3.664}});
if(m.type==='reservation_ack'){writeFileSync(${JSON.stringify(marker)},'started');process.send({type:'result',value:{ok:true}});}});`);
		let recorded = false;
		const result = await superviseAttempt(entry, {}, policy, (value) => {
			assert.equal(existsSync(marker), false);
			assert.equal(value.maximum, 3.664);
			recorded = true;
		});
		assert.equal(recorded, true);
		assert.deepEqual(result, { ok: true });
		assert.equal(await readFile(marker, "utf8"), "started");
	} finally { await rm(root, { recursive: true, force: true }); }
});

test("actual runner worker reports setup failure without invoking a provider", async () => {
	const root = await mkdtemp(join(tmpdir(), "budget-runner-"));
	try {
		const progress: any[] = [];
		await assert.rejects(superviseAttempt(fileURLToPath(new URL("./run.ts", import.meta.url)), {
			policy, protocol: 2, scratch: join(root, "scratch"), prompt: "must never reach a provider",
			contestant: { provider: "nonexistent-test-provider", model: "none" }, estate: root,
		}, policy, (value) => progress.push(value)), (error: any) => error.status === "setup_failed" && /model not in pi registry/.test(error.message));
		assert.ok(progress.length > 0);
		assert.equal(progress.at(-1).stats.cost, 0);
		assert.equal(progress.at(-1).exposure.unknown_usage, false);
	} finally { await rm(root, { recursive: true, force: true }); }
});
