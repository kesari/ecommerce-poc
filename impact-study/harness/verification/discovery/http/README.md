# Protected discovery HTTP cases

These sources are evaluator material and must remain outside every agent/tool
estate and index. `verify_http.py` extracts exact commits and verifies tree IDs
from `harness/indexes/pins.json`; it never modifies the pinned repositories.
Every attempt requires a fresh output directory outside both study and estate,
and independently reviewed SHA-256 pins for the criteria and protected Java test.

Cases H01/H02 introduce new wire keys through `@JsonProperty`, preserving provider
Java member names. H01 proves the actual Basket `CatalogClient.lookup` result;
H02 proves actual Order `ShipmentClient` consumption and checkout quote amount.
H07 changes the same typed Shipment wire amount to whole INR and proves conversion
back to minor units in quotes at zero and two positive values. H08 changes the
nonmetro default delivery SLA from four/six days to five/seven days: the unchanged
consumer already propagates it, so this case requires no coordinated edit.

Each origin is compiled in full before boundary execution. Provider DTOs and
estimator copied into test source come from that phase's actual origin archive,
not hand-written boundary reimplementations. Actual DTO Bean Validation runs on
the Shipment request before calling the real estimator. Loopback transport
replaces Spring deployment/auth; actual HTTP clients and actual CheckoutService
run in the consumer archive. Account/Basket/quote persistence ports unrelated to
the changed boundary are controlled in-memory fixtures. This does not establish
complete deployed checkout, database, broker, proxy or web behavior.

Baseline and requested-change observations are retained. Sufficient repairs and
faulted repairs run the same protected assertions. Changed phases explicitly
observe an expected regression for H01/H02/H07; H08 instead asserts the requested
new SLA reaches quotes. H01 wrong alias, H02 wrong alias, H07 incorrect conversion,
and H08 swapped dates must fail the business assertion rather than infrastructure.
H08's `repaired` phase is an identical no-edit validation phase.

These are new patches, not old benchmark diffs: H01 is conceptually related to
REST contract renames and REST-002 Catalog pricing but changes currency at a new
wire location; H02 changes Shipment estimate response rather than old Account
postalCode; H07 is a unit semantic mutation without a rename; H08 shares the
DeliveryEstimator class with old INT-001 but changes its default SLA rather than
its PIN minimum. Family generalization is limited by these shared estate paths.

Portable evidence copies only top-level artifacts and the manifest, not Maven
build outputs or runtime source archives. The manifest retains exact source-file
hashes, patches, commands/exits, observations, JUnit results and snapshot hashes.
Failed attempts remain in their own evidence directories. A digest is an
integrity binding; independent approval of its protected criteria is separate.
