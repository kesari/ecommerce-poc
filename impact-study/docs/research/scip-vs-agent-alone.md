# SCIP versus an agent alone

On REST-001, a competent agent with no code-intelligence index scores **0.98**
at **~50,000 tokens**. The same agent given the real scip-java index scores
**0.98** at **~64,000 tokens**, and finds fewer symbols and fewer tests.

The index does not move the ceiling. It costs about 29% more to reach the same
answer.

## What was compared

Both arms are the same model (`gpt-5.6-terra`), the same record, the same
runner, and the same agent instructions apart from one added section. Both run
in an isolated copy of the ten repositories with `ecommerce-poc` excluded, so
neither can read the ground truth and neither can report the mirror of every
service as affected. Both answers are graded by the same `score.py`.

| | control | SCIP arm |
|---|---|---|
| runner | `run/direct.sh` | `run/direct-scip.sh` |
| agent | `agents/impact-analyst.md` | `agents/impact-analyst-scip.md` |
| index | none | `estate.scip`, copied into the scratch estate |

The SCIP agent file is the control's text plus a section giving the operations,
the partial-name rule, and the index's three limits.

## Result

Five control runs, three SCIP runs.

| | control (n=5) | SCIP (n=3) |
|---|---|---|
| **composite** | **0.98** | **0.98** |
| **tokens** | **49,612** | **64,067** |
| cross_repo_recall | 1.00 | 1.00 |
| contract_recall | 1.00 | 1.00 |
| symbol_recall | 1.00 | 0.75 |
| test_recall | 1.00 | 0.67 |
| precision | 0.88 | 0.87 |
| critical_penalty | 1.00 | 1.00 |
| findings | 16 | 14 |
| exec blocks | 8 | 9 |
| scip-search calls | 0 | 4–6 |

Control composites: 0.98, 0.96, 0.97, 1.00, 1.00.
SCIP composites: 0.98, 0.99, 0.98.

The SCIP agent used the index properly — four to six distinct queries per run
(`symbols`, `references`, `callers`, `impact` on `AddressResponse`,
`AddressSnapshot` and `AccountClient`), with simple names rather than
fully-qualified ones. This is not a case of a tool being offered and ignored.

## Why the index cannot help here

REST-001 renames a JSON field. Its ground truth is 4 repositories, 4 symbols,
3 contracts and 3 tests. The index covers Java symbols in eight services. It
sees no YAML, no JSON schema, no `.bru` file and no TypeScript, and it does not
cover `commerce-web` or `commerce-platform` at all.

So six of the fourteen ground-truth items — every contract and every test —
are outside what the index can answer. The control already scores 1.00 on all
four recall metrics using text search alone, which leaves the index nothing to
add.

There is a second, structural limit. These services compile separately as
independent Maven projects, so the aggregated index carries no matching symbol
identity across a service boundary: `references --symbol` on the exact
`AddressResponse` id returns 0 groups where `--name AddressResponse` returns
24. Every cross-service link — which is the entire question — is agent
reasoning, never something the index reported.

## The uncomfortable part

The index did not merely fail to help. On the two metrics it exists to serve,
the arm with the index scored **lower**: `symbol_recall` 0.75 against 1.00, and
`test_recall` 0.67 against 1.00.

The likely mechanism is attention rather than capability. Index calls displace
file exploration, and the index answers compiler-shaped questions, so effort
moves toward what it can answer and away from the `.bru` scenarios and YAML
contracts where a third of the ground truth lives.

A separate one-run comparison run by hand found the opposite on tests — the
SCIP arm found `OrderSagaIntegrationTest` where the control missed it, at the
cost of three extra findings and ~3k tokens. Both readings are small samples of
a metric that was bimodal until recently. **Treat the direction of the
symbol/test gap as unresolved.** What both agree on is that the composite is
flat and the index costs more.

Both arms miss `com.poc.account.api.dto.AddressRequest` — an index-visible Java
DTO. That is the sharpest single observation against the index adding value
here: the one finding a compiler index should have made trivial, it did not
make.

## How this differs from the earlier board

Four weeks of this study compared SCIP, Gortex and Graphify against an
agent-only control through the pi-SDK harness, and measured uplift of +0.012,
+0.003 and −0.005. That null is real but weak, because every arm ran under a
harness that held the agent down: reasoning disabled (`FIXED_THINKING = "off"`)
and a toolset with no shell composition, producing 54 tool calls and 260,854
tokens for a 0.93.

This comparison runs both arms at the ceiling instead. The control reaches 0.98
at a fifth of the cost. The null is now a much stronger statement: not "the
products do not help a hobbled agent", but **"a real compiler index adds
nothing to a competent one, and costs 29% more."**

## INT-001: the record where the index should have won

REST-001 is a weak test for SCIP, so the obvious objection was that the index
lost on a record built to defeat it. `INT-001` removes that objection. It
narrows a PIN validation regex inside `shipment-service`: **one repository,
two Java symbols, zero contracts, two Java test classes.** No YAML, no `.bru`,
no TypeScript, no cross-service boundary. Every blind spot blamed above is
absent.

| | control (n=3) | SCIP (n=3) |
|---|---|---|
| composite | 0.83 | **0.84** |
| tokens | 99,903 | **137,561** |
| symbol_recall | 0.50 | 0.50 |
| test_recall | 0.50 | 0.50 |
| precision | 0.14 | 0.20 |
| scip calls | 0 | 11 |

Control composites: 0.83, 0.83, 0.83. SCIP: 0.87, 0.83, 0.84.

**+0.01 composite for +38% tokens.** The index does exactly the one thing text
search cannot: `ShipmentService` is reachable only by following a call from
`DeliveryEstimator`, and SCIP found it in 1 of 3 runs against the control's 0
of 3. It then lost `ShipmentIntegrationTest` in 3 of 3, which the control
found. The gain and the loss cancel.

So the result holds on the record designed to favour the index as well as the
one designed against it.

## The agent is overfit to REST-001

The more consequential finding on INT-001 is not about SCIP.

| | REST-001 | INT-001 |
|---|---|---|
| composite | 0.98 | 0.83 |
| symbol_recall | 1.00 | 0.50 |
| test_recall | 1.00 | 0.50 |
| **precision** | **0.88** | **0.14** |

Precision collapsed from 0.88 to 0.14 — roughly sevenfold over-reporting
against a four-item ground truth — and the control's composites are 0.83, 0.83,
0.83, so this is not variance. Seven rounds of tuning against a fourteen-item
cross-service field rename produced an agent that sweeps the whole estate and
reports the wide blast radius that record taught it to expect. On a
single-service refactor, that is mostly noise.

The instructions that generalised are the structural ones — sweep first,
ownership over string equality, close the repository set. What did not
generalise is the expectation of breadth.

**This matters more than the product comparison.** The agent needs generality
work before any further product evaluation is worth running, because a
benchmark whose baseline is tuned to one record measures the tuning.

## Limits of this result

- **Two records**, REST-001 and INT-001, chosen as the cases where the index
  should respectively lose and win. `DB-001` and the Kafka records are
  untested.
- **n=5 against n=3**, and `symbol_recall` and `test_recall` are unweighted in
  the composite, which is why the composites tie despite the gap.
- **This is SCIP only.** Gortex and Graphify have not been run under the agent
  runner at all. Gortex in particular answers contract and topic questions,
  which is exactly the ground truth SCIP is blind to, so the result here does
  not transfer to it.
- **The control is tuned and the SCIP arm inherits that tuning.** Seven rounds
  went into `impact-analyst.md`; the SCIP section had none. A tuned SCIP agent
  might close the token gap, though it has no recall left to win.

## Recommendation

For change-impact analysis of this shape, on this estate: **an agent with
ripgrep and a disciplined method is sufficient, and adding a compiler index is
a cost with no measured benefit.**

Before generalising that, run `INT-001` — a single-service internal refactor,
where the index's blind spots do not apply and its symbol resolution should
matter. If SCIP does not win there, it does not win here at all.
