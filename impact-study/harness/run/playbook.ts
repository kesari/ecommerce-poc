/** Track C, phase A: a frozen product-native query playbook.
 *
 * The harness — not the model — queries the product first, so that product
 * evidence is guaranteed to enter the analysis instead of depending on the
 * agent choosing to ask. Track B measured that choice and found it is rarely
 * made: across the REST-001 pilot Graphify returned ground-truth evidence on
 * every one of eleven successful queries and the agent credited it with none.
 *
 * Every product receives the same four query intentions. The commands differ
 * because the products expose different native surfaces; the intentions do not.
 */

/** Java/JS identifiers, plus dotted and slashed paths and topic names. */
const IDENTIFIER = /[A-Za-z_$][A-Za-z0-9_$]*(?:[./][A-Za-z0-9_$]+)*/g;

/** Words that carry no search value: language keywords and container types. */
const NOISE = new Set([
	"public", "private", "protected", "static", "final", "record", "class", "interface",
	"enum", "void", "int", "long", "double", "float", "boolean", "char", "byte", "short",
	"String", "Object", "List", "Map", "Set", "Optional", "UUID", "Instant", "LocalDate",
	"BigDecimal", "of", "return", "if", "else", "new", "this", "true", "false", "null",
	"import", "package", "toString", "get", "set",
]);

/** A quoted literal is worth searching for only when the whole literal reads as
 *  a name — a Kafka topic like order.confirmed.v1, an enum like OUT_OF_STOCK.
 *  Scraping fragments out of a literal invents anchors that mean nothing:
 *  "shipment:estimate:v2:" would otherwise yield `v2`, and "[0-9]{4,10}" would
 *  yield `0`. Take the literal whole or not at all. */
const QUOTED = /"([^"]*)"/g;
const NAME_LIKE = /^[A-Za-z][A-Za-z0-9_.-]*$/;

function identifiers(lines: string[]) {
	const found = new Set<string>();
	for (const line of lines) {
		for (const match of line.matchAll(QUOTED)) {
			if (NAME_LIKE.test(match[1]) && !NOISE.has(match[1])) found.add(match[1]);
		}
		// Tokenize with literals removed, so nothing inside quotes leaks out.
		for (const match of line.replace(QUOTED, '""').matchAll(IDENTIFIER)) {
			let token = match[0];
			// `userId.toString` is a call, not a name: drop a trailing noise segment.
			const segments = token.split(".");
			if (segments.length > 1 && NOISE.has(segments[segments.length - 1])) {
				token = segments.slice(0, -1).join(".");
			}
			if (!NOISE.has(token) && token.length > 1) found.add(token);
		}
	}
	return found;
}

export interface ChangeAnchor {
	/** The identifier the playbook searches for. */
	primary: string | null;
	/** Everything the diff changed, for reporting. */
	removed: string[];
	added: string[];
	repo: string;
	/** False when the diff renames nothing, so no product can be asked. */
	usable: boolean;
}

/** Derive the anchor mechanically, so no human judgement about the expected
 *  answer can leak into what the product is asked. A diff that renames nothing
 *  yields usable: false — INT-001, INT-002 and KAFKA-004 change only a literal
 *  or a comment, and are excluded from Track C rather than given a made-up
 *  starting word. */
export function deriveAnchor(repo: string, diff: string): ChangeAnchor {
	const lines = diff.split("\n");
	const minus = identifiers(lines.filter((line) => line.startsWith("-")));
	const plus = identifiers(lines.filter((line) => line.startsWith("+")));
	const removed = [...minus].filter((token) => !plus.has(token));
	const added = [...plus].filter((token) => !minus.has(token));
	// Search for what the estate still contains: the pre-change name is the one
	// with references, so it anchors retrieval better than the new name.
	const primary = removed[0] ?? added[0] ?? null;
	return { primary, removed, added, repo, usable: primary !== null };
}

export interface PlaybookStep {
	/** Which of the four shared intentions this step serves. */
	intention: "locate" | "references" | "expand" | "tests";
	tool: string;
	params: Record<string, unknown>;
}

/** The same four intentions per product, in each product's native surface.
 *  A product with no native answer for an intention simply has no step for it;
 *  that absence is a finding, not a gap to paper over. */
export function playbookFor(
	kind: "scip" | "gortex" | "graphify",
	anchor: ChangeAnchor,
	/** The record's frozen question, written before any testing. Every
	 *  contestant already receives this text in its prompt, so spending it on a
	 *  product's natural-language surface adds no knowledge — it only stops that
	 *  surface being starved by a one-token anchor. */
	question = "",
): PlaybookStep[] {
	const name = anchor.primary as string;
	if (kind === "scip") {
		// A symbol index answers symbol-shaped questions; a single identifier is
		// the right input here, and the first playbook confirmed it (9 of 14).
		return [
			{ intention: "locate", tool: "scip_search", params: { operation: "symbols", name } },
			{ intention: "references", tool: "scip_search", params: { operation: "references", name } },
			{ intention: "expand", tool: "scip_search", params: { operation: "callers", name } },
			{ intention: "expand", tool: "scip_search", params: { operation: "impact", name } },
			// scip-search exposes no test-oriented operation.
		];
	}
	if (kind === "gortex") {
		// Three symbol-shaped steps never reached the contract bridge Gortex is
		// built around, so it surfaced 4 of 14 where its own agent-chosen
		// queries reached 8-9. Give the bridge and the contract list a step each.
		return [
			{ intention: "locate", tool: "gortex_query", params: { operation: "symbol", query: name, repo: anchor.repo } },
			{ intention: "references", tool: "gortex_query", params: { operation: "usages", query: name, repo: anchor.repo } },
			// The contract list is Gortex's densest single answer — 8 of 14
			// ground-truth items in the pilot — and at ~16KB it is the first
			// casualty of the per-run budget. Spend the budget on it early.
			{ intention: "expand", tool: "gortex_contracts", params: { action: "list", repo: anchor.repo } },
			{ intention: "expand", tool: "gortex_contracts", params: { action: "bridge_rank", query: name, repo: anchor.repo } },
			{ intention: "expand", tool: "gortex_query", params: { operation: "dependents", query: name, repo: anchor.repo } },
		];
	}
	// Graphify's query surface is natural-language and traverses from every term
	// it can resolve. The one-token anchor starved it to 1 of 14, against 4-7
	// when the agent composed its own question. Compose one mechanically.
	const composed = `${anchor.repo} ${name} consumers across repositories`;
	return [
		{ intention: "locate", tool: "graphify_query", params: { operation: "query", query: composed } },
		{ intention: "references", tool: "graphify_query", params: { operation: "affected", query: name } },
		{ intention: "expand", tool: "graphify_query", params: { operation: "explain", query: name } },
		{ intention: "expand", tool: "graphify_query", params: { operation: "query", query: question || composed } },
		{ intention: "tests", tool: "graphify_query", params: { operation: "query", query: `${name} test coverage in ${anchor.repo}` } },
	];
}

/** Run the playbook against the product's own tools, recording a receipt per
 *  step. A step that fails is kept as negative product evidence and does not
 *  stop the rest — an empty or failing answer is a real product result. */
export async function runPlaybook(tools: any[], steps: PlaybookStep[]) {
	const outcomes: { step: PlaybookStep; ok: boolean; error?: string }[] = [];
	for (const step of steps) {
		const tool = tools.find((candidate: any) => candidate.name === step.tool);
		if (!tool) {
			outcomes.push({ step, ok: false, error: `no such tool: ${step.tool}` });
			continue;
		}
		try {
			await tool.execute("playbook", step.params);
			outcomes.push({ step, ok: true });
		} catch (error) {
			outcomes.push({ step, ok: false, error: String((error as Error)?.message ?? error).slice(0, 200) });
		}
	}
	return outcomes;
}

/** The phase-B prompt section: what the product was asked, and what it said.
 *  The agent verifies these candidates rather than discovering them. */
export function candidateSection(anchor: ChangeAnchor, receipts: any[]) {
	const lines = [
		"## Product evidence already gathered",
		"",
		`The change anchor is \`${anchor.primary}\` in \`${anchor.repo}\`.`,
		"Before you were asked, the harness ran a fixed query playbook against the",
		"product and recorded every result below. This evidence is already yours —",
		"you do not need to rediscover it.",
		"",
		"Your job is to verify it. Confirm the candidates that are genuinely in the",
		"blast radius, reject the ones that are not, and find whatever the product",
		"missed. Cite a receipt id on any finding you take from this evidence, and",
		"attribute anything you establish yourself as `file_search` or `agent_inferred`.",
		"An empty or failed product result is a real result; report it as such rather",
		"than treating it as a reason to distrust the rest.",
		"",
	];
	for (const receipt of receipts) {
		lines.push(`### [receipt ${receipt.id}] ${receipt.tool} · ${receipt.operation}`);
		const asked = receipt.requested_query ?? JSON.stringify(receipt.parameters);
		lines.push(`asked: ${asked}${receipt.executed_query && receipt.executed_query !== receipt.requested_query ? ` (executed as \`${receipt.executed_query}\`)` : ""}`);
		if (!receipt.success) {
			lines.push(`failed: ${receipt.error}`);
		} else {
			lines.push("```");
			lines.push(receipt.output ?? "");
			lines.push("```");
		}
		lines.push("");
	}
	return lines.join("\n");
}
