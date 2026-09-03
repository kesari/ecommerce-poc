// Precomputed deterministic indexes for index-backed contestants.
//
// Built at run time from the isolated estate copy, so every run stays blind,
// fresh, and reproducible. Two flavors:
//
// - symbol index (SCIP-like): Java package + top-level type declarations
//   mapped to FQN -> { repo, file, line }. Deterministic compiler-adjacent
//   truth: no inference, only declarations the source contains.
// - contract graph (Gortex-like): REST paths from OpenAPI documents and
//   Spring mapping annotations, plus Kafka topics from AsyncAPI channel
//   operations (publish: = producer, subscribe: = consumer), topic
//   definitions, and listener references, each with the evidence
//   that produced the edge. Schema copies alone confer no role.
import { createHash } from "node:crypto";
import { glob, mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

export type IndexMode = "symbol" | "contract";

export const INDEX_DIR = ".harness-index";
export const SYMBOL_INDEX_FILE = "symbols.json";
export const CONTRACT_GRAPH_FILE = "contracts.json";

export interface SymbolEntry {
	fqn: string;
	repo: string;
	file: string;
	line: number;
	kind: string;
}

export interface ContractTopic {
	topic: string;
	producers: string[];
	consumers: string[];
	declared_by: string[];
	evidence: string;
}

export interface ContractEndpoint {
	method: string;
	path: string;
	repos: string[];
	evidence: string;
}

function sha256Hex(value: string | Buffer) {
	return createHash("sha256").update(value).digest("hex");
}

function compareEntries(left: SymbolEntry, right: SymbolEntry) {
	return left.fqn < right.fqn ? -1 : left.fqn > right.fqn ? 1 : 0;
}

/** Scan one Java file for its package and top-level type declarations. */
export function parseJavaSymbols(source: string, repo: string, file: string): SymbolEntry[] {
	const entries: SymbolEntry[] = [];
	const packageMatch = source.match(/^\s*package\s+([\w.]+)\s*;/m);
	const pkg = packageMatch ? packageMatch[1] : "";
	const lines = source.split("\n");
	for (let index = 0; index < lines.length; index++) {
		const match = lines[index].match(/^\s*(?:public\s+|protected\s+|private\s+|abstract\s+|final\s+|sealed\s+)*(record|class|interface|enum)\s+([A-Z][\w]*)/);
		if (!match) continue;
		const name = match[2];
		// Skip inner declarations by requiring the match to start at column
		// depth zero: top-level types are never indented in this estate.
		if (/^\s/.test(lines[index]) && /^\s{2,}/.test(lines[index])) continue;
		entries.push({
			fqn: pkg ? `${pkg}.${name}` : name,
			repo,
			file,
			line: index + 1,
			kind: match[1],
		});
	}
	return entries;
}

/** Build the symbol index for the isolated estate copy at workdir. */
export async function buildSymbolIndex(workdir: string, repositories: readonly string[]) {
	const entries: SymbolEntry[] = [];
	for (const repo of repositories) {
		for await (const match of glob("**/*.java", { cwd: join(workdir, repo) })) {
			if (match.includes("/target/")) continue;
			const relative = `${repo}/${match}`;
			const source = await readFile(join(workdir, relative), "utf8").catch(() => null);
			if (source === null) continue;
			entries.push(...parseJavaSymbols(source, repo, relative));
		}
	}
	entries.sort(compareEntries);
	const seen = new Set<string>();
	const deduped = entries.filter((entry) => {
		const key = `${entry.fqn}\0${entry.repo}`;
		if (seen.has(key)) return false;
		seen.add(key);
		return true;
	});
	const body = `${JSON.stringify({ generated_by: "harness symbol index (SCIP-like)", symbols: deduped }, null, 2)}\n`;
	await mkdir(join(workdir, INDEX_DIR), { recursive: true });
	await writeFile(join(workdir, INDEX_DIR, SYMBOL_INDEX_FILE), body);
	return { entries: deduped, sha256: sha256Hex(body), file: `${INDEX_DIR}/${SYMBOL_INDEX_FILE}` };
}

function sortedUnique(values: string[]) {
	return [...new Set(values)].sort();
}

/** Build the contract graph for the isolated estate copy at workdir. */
export async function buildContractGraph(workdir: string, repositories: readonly string[]) {
	const endpoints: ContractEndpoint[] = [];
	const endpointSeen = new Set<string>();
	const topicRefs = new Map<string, { producers: string[]; consumers: string[]; declared_by: string[]; evidence: string[] }>();

	const noteTopic = (topic: string, repo: string, role: "producer" | "consumer" | "declared", evidence: string) => {
		let record = topicRefs.get(topic);
		if (!record) {
			record = { producers: [], consumers: [], declared_by: [], evidence: [] };
			topicRefs.set(topic, record);
		}
		if (role === "producer") record.producers.push(repo);
		else if (role === "consumer") record.consumers.push(repo);
		else record.declared_by.push(repo);
		record.evidence.push(evidence);
	};

	for (const repo of repositories) {
		// OpenAPI paths: two-space-indented "/path:" lines under paths:.
		for await (const match of glob("openapi/*.yaml", { cwd: join(workdir, repo) })) {
			const relative = `${repo}/${match}`;
			const text = await readFile(join(workdir, relative), "utf8").catch(() => null);
			if (text === null) continue;
			for (const line of text.split("\n")) {
				const pathMatch = line.match(/^ {2}(\/[A-Za-z0-9_{}/.-]*):\s*$/);
				if (!pathMatch) continue;
				const key = `*\0${pathMatch[1]}`;
				if (endpointSeen.has(key)) continue;
				endpointSeen.add(key);
				endpoints.push({ method: "*", path: pathMatch[1], repos: [repo], evidence: relative });
			}
		}
		// Spring mapping annotations with literal paths.
		for await (const match of glob("src/main/**/*.java", { cwd: join(workdir, repo) })) {
			const relative = `${repo}/${match}`;
			const text = await readFile(join(workdir, relative), "utf8").catch(() => null);
			if (text === null) continue;
			const lines = text.split("\n");
			for (let index = 0; index < lines.length; index++) {
				const annotation = lines[index].match(/@(PostMapping|GetMapping|PutMapping|DeleteMapping|PatchMapping)(?:\s*\(\s*(?:value\s*=\s*)?"([^"]+)")?/);
				if (annotation && annotation[2]) {
					const key = `${annotation[1]}\0${annotation[2]}`;
					if (!endpointSeen.has(key)) {
						endpointSeen.add(key);
						endpoints.push({
							method: annotation[1].replace("Mapping", "").toUpperCase(),
							path: annotation[2],
							repos: [repo],
							evidence: `${relative}:${index + 1}`,
						});
					}
				}
				for (const topicMatch of lines[index].matchAll(/"([a-z][a-z0-9.-]*\.v\d+(?:\.retry\.[12]|\.dlq)?)"/g)) {
					noteTopic(topicMatch[1], repo, "consumer", `${relative}:${index + 1}`);
				}
			}
		}
		// AsyncAPI channel operations: publish: means this service publishes
		// the topic (producer); subscribe: means it consumes it. Schema-copy
		// files under asyncapi/schemas confer no role on their own.
		for await (const match of glob("asyncapi/*.yaml", { cwd: join(workdir, repo) })) {
			const relative = `${repo}/${match}`;
			const text = await readFile(join(workdir, relative), "utf8").catch(() => null);
			if (text === null) continue;
			const lines = text.split("\n");
			let channel: string | null = null;
			for (let index = 0; index < lines.length; index++) {
				if (/^[^\s]/.test(lines[index])) channel = null;
				const channelMatch = lines[index].match(/^ {2}([a-z][a-z0-9.-]*):\s*$/);
				// Channel names are topic names and always contain a dot;
				// this skips sibling keys such as servers: or components:.
				if (channelMatch && channelMatch[1].includes(".")) {
					channel = channelMatch[1];
					continue;
				}
				if (channel && /^ {4}(publish|subscribe):\s*$/.test(lines[index])) {
					noteTopic(channel, repo, lines[index].trim().startsWith("publish") ? "producer" : "consumer", `${relative}:${index + 1}`);
				}
			}
		}
		// AsyncAPI schema copies declare the contract shape without
		// conferring a producer or consumer role.
		for await (const match of glob("asyncapi/schemas/*.json", { cwd: join(workdir, repo) })) {
			const topic = match.replace(/^.*\//, "").replace(/\.json$/, "");
			noteTopic(topic, repo, "declared", `${repo}/${match}`);
		}
		for await (const match of glob("kafka/topic-definitions.yaml", { cwd: join(workdir, repo) })) {
			const relative = `${repo}/${match}`;
			const text = await readFile(join(workdir, relative), "utf8").catch(() => null);
			if (text === null) continue;
			for (const nameMatch of text.matchAll(/-\s*name:\s*([a-z][a-z0-9.-]*)/g)) {
				noteTopic(nameMatch[1], repo, "declared", relative);
			}
		}
	}

	const topics: ContractTopic[] = [...topicRefs.entries()]
		.filter(([topic]) => !topic.endsWith(".retry.1") && !topic.endsWith(".retry.2") && !topic.endsWith(".dlq"))
		.map(([topic, record]) => ({
			topic,
			producers: sortedUnique(record.producers),
			consumers: sortedUnique(record.consumers.filter((repo) => !record.producers.includes(repo))),
			declared_by: sortedUnique(record.declared_by),
			evidence: sortedUnique(record.evidence).slice(0, 8).join("; "),
		}))
		.sort((left, right) => (left.topic < right.topic ? -1 : 1));
	endpoints.sort((left, right) => (left.path < right.path ? -1 : 1));

	const body = `${JSON.stringify(
		{
			generated_by: "harness contract graph (Gortex-like)",
			attribution: "producer has a publish: operation on the topic channel in its AsyncAPI document; consumer has subscribe: or references the topic string in src/main; declared_by only ships the schema or topic definition",
			endpoints,
			topics,
		},
		null,
		2,
	)}\n`;
	await mkdir(join(workdir, INDEX_DIR), { recursive: true });
	await writeFile(join(workdir, INDEX_DIR, CONTRACT_GRAPH_FILE), body);
	return { endpoints, topics, sha256: sha256Hex(body), file: `${INDEX_DIR}/${CONTRACT_GRAPH_FILE}` };
}

/** Prompt section pointing the model at its precomputed indexes. */
export function indexPromptSection(modes: readonly string[]) {
	if (modes.length === 0) return "";
	// Kept to two lines: small local models stop searching when the prompt
	// grows, so the pointer must be terse. Details live in the files.
	if (modes.includes("symbol") && modes.includes("contract")) {
		return "Index files built from this estate copy (read them with the read tool, cite only what you open): `.harness-index/symbols.json`, `.harness-index/contracts.json`. Answering without any tool call is invalid.";
	}
	if (modes.includes("symbol")) {
		return "Symbol index built from this estate copy (read it with the read tool, cite only what you open): `.harness-index/symbols.json`. Answering without any tool call is invalid.";
	}
	return "Contract graph built from this estate copy (read it with the read tool, cite only what you open): `.harness-index/contracts.json`. Answering without any tool call is invalid.";
}
