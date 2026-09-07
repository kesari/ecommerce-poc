# Using the SCIP index

You have `scip_search`, a query tool over one aggregated SCIP index built by
scip-java across every Java service in this estate. It is a compiler-produced
index: what it reports about a symbol is fact, not guesswork about text.

## Pass simple names

`name` matches a **literal partial name** against a symbol's display name.

- Pass `AddressResponse`, `postalCode`, `AccountClient`.
- Do **not** pass `com.poc.account.api.dto.AddressResponse`. A package-qualified
  name is narrowed to its declaring type before the query runs, so you lose the
  member you asked about and gain nothing.

If a name is too common and results are noisy, query the declaring type instead
and read its members from the result.

## Operations

| operation | answers |
|---|---|
| `symbols` | Where is this declared? Declaration site, package, full symbol identity. |
| `references` | Where is it used? Every reference the compiler resolved. |
| `implementations` | What implements or overrides it? |
| `callers` | What depends on this, incoming? |
| `callees` | What does this depend on, outgoing? |
| `graph` | Incoming and outgoing edges together, with relationship kinds. |
| `impact` | Review, dependency and test hints. Verbose — read the hints, do not re-open every file it names. |

## A worked sequence

For a renamed field, this is the whole investigation inside one service:

1. `symbols` on the field name — confirms it exists and names its declaring type.
2. `references` on that **type** — every place the compiler resolved it.
3. `impact` on the type — review and test hints you would otherwise hunt for.
4. Repeat from step 2 on each type the trail reaches.

Four calls will usually map a service. Then move outward by contract, not by
symbol, because of the limitation below.

## Query before you read

Within a single service the index is complete. If you want the usages of a
symbol, `references` returns all of them — grepping for the same name finds the
same lines plus false matches in comments, strings and unrelated types.

So: **ask the index first, and read a file only when you need to see logic the
index cannot express** — a null check, a mapping rule, a guard condition. Do not
grep for a symbol you can query, and do not open a file merely to confirm a
reference the index already reported.

Every result already carries the path, line and column of what it found. That is
citable evidence exactly as it stands: write `account-service/src/.../Address.java:23`
straight from the result. You do not need to open a file to learn where
something is — only to learn what it *does*.

## What the index cannot tell you

The services here are compiled separately, as independent Maven projects. The
aggregated index carries **no matching symbol identities across service
boundaries**: `AddressResponse` in `account-service` and the `AddressResponse`
that `order-service` binds by name are different symbols to the compiler, and
no reference edge joins them.

Two consequences:

1. Cross-service links are never something the index told you. You reason them
   from contracts, JSON field names and event schemas.
2. An empty result for a cross-service query is **not** evidence that nothing is
   affected. It means the compiler did not link the two — which is the situation
   this whole question is about.

Within one service, an empty result does mean nothing references the symbol.

## Attribution

- `product_direct` — you read it straight out of a `scip_search` result:
  declaration sites, references, callers, implementations, the hints `impact`
  returned. Within a service these are index facts, so claim them. Carry the
  `receipt_id` printed at the top of that result.
- `file_search` — you established it with `read`, `grep`, `find` or `ls`.
- `agent_inferred` — you joined two things: any cross-service conclusion, and
  anything you concluded from a contract or schema rather than from a symbol.

Do not downgrade an index fact to `agent_inferred` because the finding sits in
a cross-service answer. Label each finding by how *that* finding was
established.
