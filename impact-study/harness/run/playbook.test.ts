import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { deriveAnchor, runPlaybook } from "./playbook.ts";
import { RECORDS } from "./paths.ts";

test("literal and behavioral changes remain queryable using only public input", async () => {
	for (const id of ["INT-001", "INT-002", "KAFKA-004"]) {
		const record = JSON.parse(await readFile(join(RECORDS, `${id}.json`), "utf8"));
		const anchor = deriveAnchor(record.proposed_change.repo, record.proposed_change.diff);
		assert.equal(anchor.usable, true, id);
		assert.ok(anchor.primary);
	}
	assert.equal(deriveAnchor("shipment-service", '- return "shipment:estimate:" + hash(x);\n+ return "shipment:estimate:v2:" + hash(x);').primary, "shipment:estimate:");
	assert.equal(deriveAnchor("shipment-service", '- if (digits.matches("[0-9]{4,10}")) {\n+ if (digits.matches("[0-9]{6,10}")) {').kind, "literal");
	assert.equal(deriveAnchor("repo", "// behavior-only public change").kind, "repository");
	assert.equal(deriveAnchor("", "").usable, false);
});

test("diff headers do not become anchors and existing names stay preferred", () => {
	const anchor = deriveAnchor("account-service", "--- a/Dto.java\n+++ b/Dto.java\n- String postalCode,\n+ String postcode,");
	assert.equal(anchor.primary, "postalCode");
	assert.equal(anchor.kind, "symbol");
});

test("playbook binds the exact new receipt for successful and failed steps", async () => {
	const receipts: any[] = [{ id: "older" }];
	const tools = [{ name: "query", execute: async (_id: string, params: any) => {
		receipts.push({ id: `r${receipts.length}`, parameters: params });
		if (params.fail) throw new Error("native command failed");
	} }];
	const outcomes = await runPlaybook(tools, [
		{ intention: "locate", tool: "query", params: { name: "A" } },
		{ intention: "expand", tool: "query", params: { fail: true } },
		{ intention: "tests", tool: "missing", params: {} },
	], receipts);
	assert.deepEqual(outcomes.map((x) => x.receipt_id), ["r1", "r2", undefined]);
	assert.deepEqual(outcomes.map((x) => x.ok), [true, false, false]);
});

test("unreceipted or ambiguous native calls cannot acquire an older receipt", async () => {
	const receipts: any[] = [{ id: "old" }];
	const outcomes = await runPlaybook([
		{ name: "none", execute: async () => {} },
		{ name: "multiple", execute: async () => { receipts.push({ id: "a" }, { id: "b" }); } },
	], [
		{ intention: "locate", tool: "none", params: {} },
		{ intention: "expand", tool: "multiple", params: {} },
	], receipts);
	assert.deepEqual(outcomes.map((x) => x.receipt_id), [undefined, undefined]);
});
