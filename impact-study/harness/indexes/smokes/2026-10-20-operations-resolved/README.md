# Native operation coverage (20 October 2026)

Zero model/provider calls. All 23 operations exposed by the three active product
adapters were invoked against the pinned estate using development identifiers.
The JSON retains complete invocation receipts and source/product provenance.

- RepoWise: symbol/path search, symbol context and blast radius succeeded.
  Missing-symbol context is a successful empty result; broad `get` search is
  visibly ambiguous and is not treated as a uniquely resolved symbol.
- Codebase Memory: all six operations succeeded on valid inputs, including the
  fully qualified indexed `AccountClient.address` trace. Missing search is empty;
  short `getAddress` trace returns an explicit function-not-found error. This is
  distinct from a process/socket failure and is not proof of an absent caller.
- Gortex: all seven graph operations and five of six contract operations
  completed. Graph traversals use a returned exact node ID; broad search and
  missing search remain visible. `route_impact` returned `no route matching` for
  the development address route, including direct native path-substring probes.
  The installed `api_impact` schema accepts a route substring; its extracted
  route view did not resolve this Java route even though contracts list contains
  the OpenAPI declaration. No positive Java API-impact traversal is established.
  Preserve this as a retrieval limitation, not a successful impact result or a
  claim that the actual HTTP dependency is absent.

The earlier `2026-10-20-operations` and `...-final` directories retain setup and
query failures: daemon not running, an incorrect trace fixture, short names,
and a transient Codebase Memory socket-start failure while concurrent tests were
running. The resolved run followed sequential native checks outside the sandbox.
No administrator privileges or model credentials were used. Runtime daemons are
normal user processes; Docker Desktop was not restarted or configured here.

The corrected Gortex adapter 1.4.2 operation run is retained in
`../2026-10-20-operations-gortex-v1.4.2-verified/gortex.json`. It records
requested METHOD/path separately from the executed native path substring,
including the unresolved Java route lookup.
