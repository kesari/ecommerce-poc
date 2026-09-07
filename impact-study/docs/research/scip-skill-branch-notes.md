# SCIP skill branch: what was changed and what it measured

Branch `product/scip`. One product, one branch, and a skill that teaches the
agent to drive the index rather than an adapter that decides for it.

Everything below is measured on REST-001 (`postalCode` → `postcode` in
`account-service`), three runs per version unless stated. Every skill edit
changes `prompt_sha256` and therefore the cohort, so versions are compared
across cohorts deliberately, never pooled.

## Why the branch exists

Every product number this study produced before it measured an adapter, not a
product. Track B wrapped each product in a fixed tool schema; Track C added a
frozen playbook that had to be hand-tuned until Graphify stopped starving on a
one-token anchor. Both encode what the harness author guessed the product was
for.

Branch-per-product also fixes a concrete failure. When the harness was shared,
one edit to a shared file moved every arm into a new cohort: of 98
`(record, contestant)` cells collected in the first week, only 35 held five
runs in a single cohort, and only three records were complete across four arms.
On a per-product branch, fixing Graphify cannot invalidate SCIP's data.

## Changes

### 1. Contestants trimmed to two

`contestants.json` holds `pi-codex` (agent-only control) and
`pi-codex-scip-skill`. Every synthetic, legacy and other-product contestant is
removed. The control is duplicated per branch rather than shared, so each
branch's uplift comparison lives in one cohort.

### 2. The skill is prompt text, not a PI skill

Delivered by appending markdown to the prompt, not through PI's skill loader.
Two reasons: a scored run still inherits no local PI configuration, which is
existing harness policy; and the skill text lands inside `prompt_sha256`, so
editing a skill correctly moves the cohort instead of silently changing the
experiment.

### 3. Skill tuning, v1 → v3

| version | n | median | scip calls | reads | FQNs sent | product_direct |
|---|---|---|---|---|---|---|
| v1 | 1 | 0.74 | 7 | 36 | 2 | 1 |
| v2 | 4 | 0.87 | 8 | 33 | 0 | 6 |
| **v3** | **3** | **0.93** | 9 | **23** | 0 | 5 |
| v4 | 3 | 0.91 | 12 | 32 | 0 | — |

v3 composites: 0.93, 0.93, 0.94 — perfect repository, contract and symbol
recall, no critical misses, and near-zero variance where this record's
historical range across the study was 0.72–0.93.

Three changes moved measurable behaviour:

**Leading with "pass simple names" as an imperative** took fully-qualified
names from 2 per run to 0. The agent sends them by habit because the prompt's
own Identifier Convention asks for canonical FQNs *in answers*, and it carries
that into its queries. `scip-search` matches a literal partial name and returns
nothing for an FQN — this is why all six `references` calls in the earlier
pilot came back empty.

**Telling the agent that results already carry path, line and column** took
reads from 33 to 23. It had been opening files to obtain citable line numbers
it was already holding, because the answer schema demands evidence naming a
file and line.

**Labelling each finding by how that finding was established**, rather than by
the shape of the overall question, took `product_direct` from 1 to 6. The
earlier wording let a cross-service *question* downgrade findings that were
themselves plain index facts.

### 4. v4 reverted

v4 added a section naming the repositories the index covers, to chase
`test_recall`. Reverted: `test_recall` stayed at 0.33 across all three runs,
which was the entire objective. Its median of 0.91 against v3's 0.93 is inside
the noise, so the revert rests on the failed objective, not on the score.

### 5. Estate path made explicit

`DEFAULT_ESTATE` looked for the estate as a sibling of the repository and fell
back to the repository root. Inside the monorepo that fallback is worse than no
guess: the root holds all ten service directories as folders of one repository,
so every per-repo pin check reads the monorepo's HEAD and reports drift that is
not there. `POC_ESTATE` now takes precedence, and two freshness tests that
hardcoded the sibling path use `DEFAULT_ESTATE`.

Branch-per-product makes a non-sibling clone the normal case. Without this, a
clone cannot build its indexes or run its suite, and the failure presents as
drift rather than misconfiguration.

### 6. `--one-line` instead of `--json`

The adapter asked for `--json` on every call since it was written. The CLI's
own default for query commands is `--one-line`, which carries the same facts
the skill tells the agent to cite — path, line, column, symbol — without
restating them in a structure nothing reads.

| operation | json | one-line | |
|---|---|---|---|
| symbols | 11,444 | 7,015 | 39% smaller |
| references | 8,706 | 2,147 | 75% smaller |
| callers | 12,604 | 7,719 | 39% smaller |
| impact | 14,596 | 12,820 | 12% smaller |

Result pending at time of writing.

## Findings

### Token cost is dominated by product output, not file reads

| | tokens | tool calls | product output | reads |
|---|---|---|---|---|
| v3 skill | 260,854 | 54 | **148,487 B** | 23 |
| Track B scip (8KB cap) | 138,265 | 46 | capped | 28 |
| agent-only | 164,998 | 50 | 0 | 12 |

Three iterations of skill tuning cut reads from 36 to 23 and barely moved the
token count, because index output grew as the skill worked better: 7 calls to
9, each around 16KB. In an agentic loop every tool result stays in context and
is re-sent on each subsequent turn, so a large result injected early is paid
for many times over.

This traces to removing the per-call output ceiling. That was right for Gortex,
where an 8KB cap discarded the tail of its richest answer along with three of
the four affected repositories, but it was applied globally and SCIP's outputs
were never the problem.

### A universal miss turned out to be a ground-truth artifact

`test commerce-platform e2e-bruno/01-signup-to-confirmed-order` was missed by
every product in every Track B and Track C run, and was recorded as a shared
gap in all three indexes. It is not. No file of that name exists — the only
match in `commerce-platform` is `e2e-bruno/run-e2e.sh`. Nothing can find it by
name, so it cannot discriminate between contestants and inflates every miss
count equally.

### The index cannot link services, and the skill must say so

These services compile separately as independent Maven projects, so the
aggregated index carries no matching symbol identities across service
boundaries. `references --symbol` on the exact `AddressResponse` id returns 0
groups where `--name AddressResponse` returns 24. Any cross-service conclusion
is therefore agent reasoning over contracts and schemas, and an empty
cross-service result is not evidence of no impact — it is evidence the compiler
did not link the two, which is the situation the study exists to examine.

## Method notes

Two mistakes worth not repeating.

**A batch was analysed before it finished.** v4 was reported at n=2 as median
0.82 with a 60% token increase; at n=3 it is 0.91 with the same token cost as
v3. Both figures reached a commit message before being corrected. Wait for the
completion signal.

**Medians were computed per `(record, arm)` rather than per cohort** during the
earlier boards, which pooled runs that the cohort key exists to keep apart.
Three records reported as complete four-way ties had cells spanning five
cohorts.

## Caveats

v3 is tuned on one record. Three of its four changes are general facts about
the tool — how names match, where line numbers come from, how to attribute —
and should transfer. The fourth, the worked four-step sequence, is shaped
around a field rename and may not suit a Kafka topic or schema change.
Validate on a KAFKA or DB record before using this as the template for the
Gortex and Graphify branches.

No uplift figure is claimed here. The control arm has not been run on this
branch, so nothing on this page says whether the skill beats plain file search
— only that it beats earlier versions of itself.
