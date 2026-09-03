// Stage: give each run an isolated, fingerprinted copy of the estate, and tools
// that cannot read outside it. This is what makes a run blind and reproducible.
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import {
	access as fsAccess,
	cp,
	glob,
	lstat,
	mkdir,
	readFile,
	readdir,
	readlink,
	realpath,
	stat,
} from "node:fs/promises";
import { basename, isAbsolute, join, relative, resolve, sep } from "node:path";
import { createReadOnlyTools } from "@earendil-works/pi-coding-agent";
import { HARNESS } from "./paths.ts";

const IGNORED = new Set(["target", "node_modules", ".git", "dist", "build"]);

export const ESTATE_REPOSITORIES = [
	"account-service",
	"basket-service",
	"catalog-service",
	"commerce-bff",
	"commerce-platform",
	"commerce-web",
	"inventory-service",
	"order-service",
	"payment-service",
	"shipment-service",
] as const;

const repositoryRoot = resolve(HARNESS, "..", "..");
const siblingEstate = join(repositoryRoot, "POC-order-microservices");
export const DEFAULT_ESTATE = ESTATE_REPOSITORIES.every((name) => existsSync(join(siblingEstate, name)))
	? siblingEstate
	: repositoryRoot;

export interface EstateRevision {
	name: string;
	commit: string | null;
	dirty: boolean | null;
}

export interface EstateSnapshot {
	sha256: string;
	repositories: string[];
	revisions: EstateRevision[];
}

export async function assertDirectory(path: string, label: string) {
	let value;
	try {
		value = await stat(path);
	} catch {
		throw new Error(`${label} not found: ${path}`);
	}
	if (!value.isDirectory()) throw new Error(`${label} is not a directory: ${path}`);
}

/** Copy the allowlisted repositories into scratch, without their build output. */
export async function isolate(
	estate: string,
	scratch: string,
	repositories: readonly string[] = ESTATE_REPOSITORIES,
) {
	await assertDirectory(estate, "estate");
	for (const name of repositories) {
		await assertDirectory(join(estate, name), `estate repository ${name}`);
	}
	const target = join(scratch, "estate");
	await mkdir(target);
	for (const name of repositories) {
		await cp(join(estate, name), join(target, name), {
			recursive: true,
			filter: (source) => !IGNORED.has(basename(source)),
		});
	}
	return target;
}

export async function createPathGuard(root: string) {
	const rootPath = await realpath(root);
	return async (candidate: string) => {
		const candidatePath = await realpath(candidate);
		const fromRoot = relative(rootPath, candidatePath);
		const outside = fromRoot === ".." || fromRoot.startsWith(`..${sep}`) || isAbsolute(fromRoot);
		if (outside) throw new Error(`path outside isolated estate: ${candidate}`);
		return candidatePath;
	};
}

/** PI's read-only tools, with every filesystem operation forced through the guard. */
export async function createRestrictedReadOnlyTools(root: string) {
	const inside = await createPathGuard(root);
	const isInside = async (path: string) => {
		try {
			await inside(path);
			return true;
		} catch {
			return false;
		}
	};
	return createReadOnlyTools(root, {
		read: {
			operations: {
				access: async (path) => fsAccess(await inside(path)),
				readFile: async (path) => readFile(await inside(path)),
			},
		},
		grep: {
			operations: {
				isDirectory: async (path) => (await stat(await inside(path))).isDirectory(),
				readFile: async (path) => readFile(await inside(path), "utf8"),
			},
		},
		find: {
			operations: {
				exists: isInside,
				glob: async (pattern, cwd, options) => {
					const safeCwd = await inside(cwd);
					const effectivePattern = pattern.includes("/") || pattern === "**" ? pattern : `**/${pattern}`;
					const results: string[] = [];
					for await (const match of glob(effectivePattern, { cwd: safeCwd })) {
						if (results.length >= options.limit) break;
						if (match.split("/").some((part) => IGNORED.has(part))) continue;
						const path = await inside(resolve(safeCwd, match));
						if ((await stat(path)).isFile()) results.push(path);
					}
					return results;
				},
			},
		},
		ls: {
			operations: {
				exists: isInside,
				stat: async (path) => stat(await inside(path)),
				readdir: async (path) => readdir(await inside(path)),
			},
		},
	});
}

async function fingerprintDirectory(root: string, hash: ReturnType<typeof createHash>, prefix = "") {
	const entries = (await readdir(root, { withFileTypes: true })).sort((left, right) =>
		left.name.localeCompare(right.name),
	);
	for (const entry of entries) {
		if (IGNORED.has(entry.name)) continue;
		const path = join(root, entry.name);
		const relativePath = prefix ? `${prefix}/${entry.name}` : entry.name;
		const file = await lstat(path);
		hash.update(relativePath).update("\0");
		if (file.isDirectory()) await fingerprintDirectory(path, hash, relativePath);
		else if (file.isSymbolicLink()) hash.update(`symlink:${await readlink(path)}`).update("\0");
		else if (file.isFile()) hash.update(await readFile(path)).update("\0");
	}
	return hash;
}

function gitValue(repository: string, args: string[]) {
	const result = spawnSync("git", ["-C", repository, ...args], { encoding: "utf8" });
	return result.status === 0 ? result.stdout.trim() : null;
}

/** Content hash plus per-repository commit and dirty state, recorded in every answer. */
export async function captureEstateSnapshot(
	estate: string,
	repositories: readonly string[] = ESTATE_REPOSITORIES,
): Promise<EstateSnapshot> {
	const hash = createHash("sha256");
	const revisions: EstateRevision[] = [];
	for (const name of repositories) {
		const repository = join(estate, name);
		await assertDirectory(repository, `estate repository ${name}`);
		hash.update(name).update("\0");
		await fingerprintDirectory(repository, hash, name);
		const commit = gitValue(repository, ["rev-parse", "HEAD"]);
		const statusText = gitValue(repository, ["status", "--porcelain", "--untracked-files=all", "--", "."]);
		revisions.push({ name, commit, dirty: statusText === null ? null : statusText.length > 0 });
	}
	return { sha256: hash.digest("hex"), repositories: [...repositories], revisions };
}
