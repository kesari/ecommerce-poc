# Real product indexes

These artifacts are produced by the open-source products themselves. The TypeScript
indexes in `harness/run/indexes.ts` are simulations retained only for historical
comparison; they are not evidence about SCIP, Gortex, or Graphify product quality.

## Reproduce

`pins.json` is the authority for all ten repository revisions and tool versions.
Both static builders verify every repository, archive the pinned commit into a
unique temporary directory, and reject an unexpected binary or package hash.

```bash
./build-scip.sh
./build-graphify.sh
./setup-gortex.sh
```

The generated large indexes are ignored. Reviewable manifests under `manifests/`
record their SHA-256 digests, sizes, source revisions, and relevant graph counts.
Every manifest embeds the SHA-256 of `pins.json`: editing pins (revisions,
toolchain, or metadata) invalidates all three manifests, and the runner test
suite fails loudly until each manifest is regenerated with identical
artifacts and metadata via `index_admin.py manifest`.

## Query surfaces

- `scip-java + scip-search`: `symbols`, `references`, `implementations`, `graph`,
  `callers`, `callees`, and `impact` over one aggregated `estate.scip` file.
- `Gortex`: its read-only daemon query surface over the globally configured
  `poc-estate` workspace. `setup-gortex.sh` leaves no files in estate repositories.
- `Graphify`: `query`, `explain`, `path`, and `affected` over `merged-graph.json`.

The runner exposes only these allowlisted read operations. It does not give the
agent a shell or arbitrary product arguments.

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
