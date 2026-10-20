# Cross-family contract consumer closure

The broad task gives the predictor all ten repositories. A narrow proof must not
hide a readily visible additional consumer or penalize a correct extra repair.
This extension addresses that gap without changing the original protected tests.
No model calls are made.

`audit_consumers.py` reads all ten exact git archives at the pinned commits. Its
portable report hashes every tracked archive file and lists production references
to the changed contracts, their consumers and common native HTTP client APIs. This
is source evidence for a reviewed dependency inventory, not an automatic proof of
all runtime behavior. It excludes evaluator artifacts and does not alter indexes.

The reviewed production consumer inventory is:

| Change | Direct or transitive consumers | Additional edit conclusion |
| --- | --- | --- |
| H01 Catalog currency wire rename | Basket CatalogClient; BFF CatalogProxyController and commerce-web ProductsPage | **Both Basket and web need independent repairs.** BFF passes the changed shape through. |
| H02 Shipment charge key rename | Order ShipmentClient, then stable QuoteResponse through BFF to web | Order mapping repair required. QuoteResponse still exposes price.shippingMinor; no extra UI wire rename. |
| H03 Basket total wire rename | Order BasketClient and BFF BasketProxyController to web BasketPage | Only web repair required; Order's snapshot deliberately does not use response.totalMinor. |
| H04 Catalog image key rename | Basket CatalogClient; BFF catalog forwarding to ProductsPage | Only web repair required; Basket ignores the image member when constructing ProductInfo. |
| H07 Shipment amount units | Order ShipmentClient then CheckoutService/QuoteResponse | Order conversion required; QuoteResponse API fields remain stable after conversion. |
| H08 Shipment estimator defaults | ShipmentService estimate/cache and confirmed-order shipment creation; Order estimate client then quote display | No consumer edit established. Warm existing estimate cache and rollout policy are a separate origin concern, flagged to the coordinator. |
| H11 Catalog optional field | Product DTO cache roundtrip; Basket CatalogClient; BFF and web products | No coordinated edit on the executed consumers. |
| H12 Catalog list mapping implementation | CatalogService list/cache; BFF and web products | No coordinated edit; by-ID Product DTO contract is unchanged. |

Account, Inventory, Payment and the platform repository have no production client
reference to these changed HTTP contracts in the pinned source inventory. Payment
and other Kafka consumers receive stable Order/Shipment domain payloads rather
than the renamed HTTP DTOs. This statement concerns these HTTP changes, not every
possible behavior of those services.

## Executed closure checks

`verify_closure.py` requires the independently reviewed criteria/test digests. It
extracts fresh pinned archives into `/private/tmp`, executes there, and copies
only portable evidence into `closure/evidence` after execution. All command logs,
JSON captures and JUnit output originate outside the study and source estate.
Move an existing evidence directory into named history before a deliberate rerun.

H01 compiles the complete Catalog origin and executes actual CatalogService list
serialization and cache reread. Its public upstream patch is asserted byte-equal
to the original HTTP H01 patch after removing the repository path prefix. The
actual BFF controller and DownstreamRelay forward the resulting JSON over real
localhost HTTP. ProductsPage and listProducts consume that exact capture.

The baseline renders both INR prices. Changed Catalog output makes actual money
formatting throw because Product.currency is undefined. A React ErrorBoundary
catches that business failure so the test fails the explicit unchanged
`BUSINESS_PRODUCT_CURRENCY_PRICES` assertion, without an unhandled Vitest error.
Normalizing `currencyCode` into internal `Product.currency` in
`src/api/products.ts.listProducts` restores both prices. Reading `currencyWrong`
fails the same explicit assertion. Both correct and faulty versions pass the
complete web build. Basket's separate alias repair cannot repair this direct
Catalog/BFF/web route, so both H01 actions are independently necessary.

For H04 and H11, ProductClosureTest feeds the exact original web-case producer
JSON into the actual unchanged Basket CatalogClient over localhost HTTP. The
ProductInfo id/name/price/currency/active values are asserted and its complete
serialized baseline/changed snapshots must match. H04 losing an unused image
member and H11 adding an unknown optional member do not require a Basket edit.

For H03, BasketClosureTest feeds the exact original Basket provider JSON to the
actual unchanged Order BasketClient. All snapshot fields and line values are
asserted; baseline and renamed-total snapshots must match. A deserialized record
member being present in source is not enough to claim it is behaviorally used.

No deployed authentication/security chain, live backing storage, browser checkout
journey, global pagination/query forwarding or out-of-estate consumers are claimed.
H02/H07/H08 downstream web conclusions above are source audit, not additional
dynamic web executions. Their actual Order business values are independently
verified in the HTTP family.

`failed-attempt-01-order-test-accessor` preserves the initial closure attempt:
H01 and Basket controls passed, but the new Order test used `items()` where the
real snapshot accessor is `lines()`. The coordinator independently reviewed the
two-accessor correction and approved the new digest before fresh execution. No
criteria or business assertion was weakened.
