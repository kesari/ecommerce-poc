# Real product indexes

These artifacts are produced by the open-source products themselves. The TypeScript
indexes in `harness/run/indexes.ts` are simulations retained only for historical
comparison; they are not evidence about any product's quality.

## Reproduce

`pins.json` is the authority for all ten repository revisions and tool versions.
Both static builders verify every repository, archive the pinned commit into a
unique temporary directory, and reject an unexpected binary or package hash.

```bash
./install-new-products.sh
./build-repowise.sh
./build-codebase-memory.sh
./serve-repowise.sh                 # keep running while RepoWise contestants run
./build-scip.sh
./build-graphify.sh
./setup-gortex.sh
```

The first four commands are the current candidate path. The last three reproduce
historical candidates and Gortex. RepoWise and Codebase Memory use separate files
under `product-pins/`, so introducing a candidate does not invalidate unrelated,
already-recorded product artifacts. `pins.json` remains the authority for the
estate and the original products.

`install-new-products.sh` currently pins the official macOS arm64 distributions:
RepoWise 0.51.0 and Codebase Memory 0.11.0. The setup scripts keep all generated
state under this directory. RepoWise indexes pinned local clones so its history
analysis is available without writing into the estate. Codebase Memory uses an
isolated `CBM_CACHE_DIR` and writes no repository files.

RepoWise's wheel, Python 3.13.13 runtime, and complete resolved dependency set
are pinned. The installer rejects a dependency environment that differs from
`product-pins/repowise-requirements.lock`.

The generated large indexes are ignored. Reviewable manifests under `manifests/`
record their SHA-256 digests, sizes, source revisions, and relevant graph counts.
Original manifests embed the SHA-256 of `pins.json`; new candidate manifests
also embed the SHA-256 of their own product pin. Editing applicable pins invalidates
the manifest, and the runner test
suite fails loudly until each manifest is regenerated with identical
artifacts and metadata via `index_admin.py manifest`.

## Query surfaces

- `scip-java + scip-search`: `symbols`, `references`, `implementations`, `graph`,
  `callers`, `callees`, and `impact` over one aggregated `estate.scip` file.
- `Gortex`: its read-only daemon query surface over the globally configured
  `poc-estate` workspace. `setup-gortex.sh` leaves no files in estate repositories.
- `Graphify`: `query`, `explain`, `path`, and `affected` over `merged-graph.json`.
- `RepoWise`: workspace-wide structural symbol/path search, symbol context,
  plus the native
  `/api/workspace/blast-radius` endpoint. The API defaults to
  `http://127.0.0.1:7337`; override with `REPOWISE_URL`.
- `Codebase Memory`: one-shot `search_graph`, function-only `trace_path`,
  `get_graph_schema`, and bounded read-only `query_graph` calls over references,
  `CROSS_*`, and `TESTS` edges.

The runner exposes only these allowlisted read operations. It does not give the
agent a shell or arbitrary product arguments.

## Promotion gate

New contestants start disabled. Enable one only after its install and build
scripts succeed, the manifest verifies, the native smoke query returns a known
REST-001 candidate, and a product-first dry run contains one receipt per
playbook step. RepoWise 0.51.0 and Codebase Memory 0.11.0 passed this gate on
2026-09-18 and are enabled for staged scored smoke tests. Availability alone is
not product use.

## Known product behavior

The aggregate SCIP file gives one-file querying, but separately compiled Maven
services do not emit matching cross-root symbol identities in this estate. Any
service-to-service bridge inferred after a SCIP query is agent reasoning and must
be attributed as `agent_inferred`, not `product_direct`.

Graphify stores 4,028 nodes and 7,967 links. Its query loader drops 7 edges
with no node loss, so the effective query graph contains 4,028 nodes and
7,960 edges. Both counts are pinned and checked. An earlier 4,645-node
figure came from working-copy builds with unknown extra files and proved
unreproducible; archive-built graphs are the canonical source and merge
deterministically (verified by rebuilding).

Gortex keys contract nodes by workspace at index time. All ten repositories must
be tracked in `poc-estate`; changing the declaration requires a reload/reindex.
The current address bridge does not pair the account provider with consumers
through the BFF path rewrite. That is recorded as negative product evidence.
