# Web composition and compatible controls

These four independent local verifications execute pinned Java production code,
real localhost HTTP into the production BFF relay, and the actual web API and
React components. `verify.py` extracts the four source repositories at
`harness/indexes/pins.json` commits into a new `/private/tmp` directory. It copies
frontend dependencies into that directory; it does not modify the source estate,
indexes or global services. No paid/model calls are made.

Run `python3 harness/verification/discovery/web/verify.py` from the study root with
Java 21, Maven offline dependencies, Node and the pinned estate available. Maven
cache access and local sockets may need sandbox escalation. A successful evidence
directory is never overwritten. Move it to a named historical directory to make
a deliberate new execution. Execution outputs are first written under the fresh
temporary runtime and only copied into the portable evidence directory after the
run. Temporary archives are retained for diagnosis, but
no build output or dependencies are part of the portable bundle.

## Established behavior

| Case | Public origin change | Baseline | Changed | Sufficient repair | Fault |
| --- | --- | --- | --- | --- | --- |
| H03 | Basket `totalMinor` JSON key becomes `payableMinor`, Java member unchanged | Pass | Basket total display fails | Normalize `payableMinor` to internal `Basket.totalMinor` in shared basket API wrapper; pass | Reading subtotal instead fails the same visible-total assertion |
| H04 | Catalog `imageUrl` JSON key becomes `thumbnailUrl`, Java member unchanged | Pass | Images absent | Update Product type and ProductsPage image read; pass | Reading description as URL fails the same exact-image assertion |
| H11 | Add computed optional `merchandisingTag` JSON field | Pass | Pass without a coordinated edit | No consumer repair required | Removing image JSON field fails the same exact-image assertion |
| H12 | Catalog list response mapping changes from stream to equivalent ordered loop | Pass | Pass without a coordinated edit | No consumer repair required | Incrementing price in the new mapping fails the same exact-price assertion |

The changed origins all compile their complete production source and run their
protected serialization/application tests. H03, H04 repairs and their deliberately
wrong repairs also pass complete web builds: the behavioral failures are not
compiler errors. The final execution contains 35 commands, including six expected
behavioral failures. `commands.json` records every command and exit.

`BasketWireTest` serializes the actual `BasketResponse` with a concrete nonempty
basket. `CatalogWireTest` executes the actual `CatalogService.listActive` over a
controlled `ProductRepository` and `ProductCache`, then executes its actual cached
JSON read path. It verifies byte-identical list order/values and a single repository
lookup. Provider and cache-hit JSON files are retained. H11's read-only computed
property is intentionally part of the complete upstream patch so its cache read
continues to work.

`ForwardWireTest` serves those exact bytes over localhost HTTP and invokes the
actual Basket/Catalog proxy controller via MockMvc with the real DownstreamRelay.
It asserts one real downstream request, HTTP 200 and exact JSON tree equality,
then captures the BFF response. BFF CatalogCache is controlled to miss. Real BFF
routing through a deployed servlet/security/filter chain is not claimed.

`discovery-wire.test.tsx` supplies the exact captured BFF bytes at the transport
boundary. The production `apiRequest`, `getBasket`/`listProducts`, `BasketPage` and
`ProductsPage` run in jsdom. It verifies the actual requested URL, basket amount,
product order, image URLs and prices. There is no browser/login/checkout journey,
live repository database or live Valkey claim. H03 repairs all six response
wrappers by source inspection/build; only current-basket GET display is executed.

## Scoring facts and boundaries

- H03 directed dependencies proved: `basket-service -> commerce-bff`,
  `commerce-bff -> commerce-web`, REST `GET /api/v1/basket`, baseline field
  `totalMinor`. Only `commerce-web` breaks on this executed surface and needs a
  coordinated production edit. Origin and BFF are validation-only. The sufficient
  action is reading `payableMinor` in the basket response API path while preserving
  the internal total. The added `basketRequest` normalizer is a proved repair;
  `src/api/basket.ts.getBasket` is the existing baseline source anchor. Targets
  for newly introduced helpers should not silently replace baseline anchors.
- H04 dependencies proved: `catalog-service -> commerce-bff`,
  `commerce-bff -> commerce-web`, REST `GET /api/v1/products?page=0&size=20`,
  baseline field `imageUrl`. Only `commerce-web` breaks and requires a production
  edit on this surface. Action: `src/pages/ProductsPage.tsx.ProductsPage`
  `read_json_field`, `imageUrl -> thumbnailUrl`, plus the matching Product type so
  the complete build succeeds. BFF and origin need validation only.
- H11/H12 prove the same Catalog/BFF/web route remains working for the specified
  ordered two-product inputs without consumer production edits. Image/name/price
  reads are independent protected assertions, and both miss and hit paths execute
  in CatalogService. These are compatible controls on this declared surface;
  their direct-consumer closure is extended by the additional checks below.
- All four mutations differ from the fifteen v1 and three v2 development diffs.
  Existing development amount-unit changes were at other contracts; H03 is a new
  response member rename. H04 and H11 alter new Catalog wire locations; H12 is a
  new private list mapping implementation change.

## Evidence and historical attempts

`evidence/criteria.json` and `protected-before.json` were written before final
execution. Tests and criteria are hash-checked again afterwards. `manifest.json`
binds portable artifacts, `source-snapshot-hashes.json` binds every pinned archive
file, and `summary.json` records the source commits and execution limits. Full
origin/repair/fault patches, Java JUnit reports, JSON and Vitest logs are retained.
H11/H12 reuse the identical H04 baseline Catalog serialization and BFF capture;
their protected component baseline checks run separately. This reuse is explicit,
not a new claimed provider execution.

Failed attempts remain separately named: first a sandbox Maven cache-lock denial,
then the added test's missing Node type reference, then the incomplete additive
getter's strict JSON cache read failure. The protected behavioral assertions did
not change to excuse an expected failure. The Node type declaration was corrected
before the final execution; the additive upstream patch was completed with
`JsonProperty.Access.READ_ONLY` before any model predictions.

The original successful run wrote captures directly into the study evidence
directory. It is preserved unchanged as `evidence-direct-output-history`. The
final rerun corrects this storage policy: all execution outputs originate in
temporary runtime directories before portable copying, with identical protected
criteria and assertions.

See [the closure extension](closure/README.md) for the all-ten-repository source
audit, H01's additional necessary web repair, and actual unchanged Basket/Order
client checks for H04/H11 and H03. These findings supersede the original narrow
boundary's unresolved direct consumers without rewriting its historical proof.
