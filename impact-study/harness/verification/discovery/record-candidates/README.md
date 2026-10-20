# Draft discovery records

`approved-proof-pins.json` contains the external proof digests independently
reviewed and supplied by the coordinating agent. The builder requires that registry
as explicit input; it never turns a newly computed digest into its own approval.
All twelve runtime cases were executed before these drafts were generated.

`build_records.py` checks those pins, all portable artifacts, protected input
bytes, actual command/JUnit/business-fault outcomes, public source snapshot
identity, consumer closure controls and exact shared H01 upstream patch equality.
It uses one neutral query plus legitimate public amount/TTL policies for H07/H08.
Every candidate remains `status: draft` until independent root freeze and oracle
construction. The new revision and public repository universe require the root's
2.2 schema/scorer integration. No current 2.1 files are changed by this builder.

`twelve-drafts-v2/` contains all twelve current candidates, with product route identities using the public path rather than concrete test query parameters. `claim-proofs.json`
binds every full record and its canonical SHA-256; changing its query, status,
source snapshot or any expected fact invalidates the binding. Every expected
repository, symbol, edge, action and obligation has concrete artifact and source
references. Static all-ten consumer inventory is supporting evidence, not an
execution claim. Original eight-case drafts are retained separately as an
intermediate construction output; they are not the final suite.

H01 requires two independent consumer repairs. H03 includes Order's unchanged
snapshot control; H04/H11 include Basket's unchanged product control. The primary
causal edges are empty for no-edit/default/private compatible controls. This does
not require an otherwise correct no-edit answer to enumerate unchanged contracts.
H07's `scale_numeric_field` uses decimal `before`/`after` multipliers of the received
value: `1` to `100`; this is a generic numeric predicate, not prose interpretation.

Generate a fresh directory (never a source estate or tool index):

```sh
python3 harness/verification/discovery/build_records.py \
  --trusted-pins harness/verification/discovery/record-candidates/approved-proof-pins.json \
  --output /private/tmp/discovery-record-candidates
python3 -m unittest discover -s harness/verification/discovery -p 'test_build_records.py'
```

No model/provider calls are performed. The suite remains bounded by each proof's
recorded execution limits; these candidates alone do not approve final paid runs.
