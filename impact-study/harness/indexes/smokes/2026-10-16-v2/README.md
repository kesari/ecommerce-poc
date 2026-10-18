# Native product adapter smoke, 16 October 2026

All three active product adapters executed the frozen product-first playbook
against the clean pinned ten-repository estate using the public v2 REST-001
question and diff. No model or paid provider was called. These files are outside
the searchable service repositories and are evaluator inputs.

| Product | Scheduled steps | Receipts | Successful native invocations |
|---|---:|---:|---:|
| Gortex | 5 | 5 | 5 |
| RepoWise | 4 | 4 | 4 |
| Codebase Memory | 5 | 5 | 5 |

The invocation used the runner's actual `captureEstateSnapshot`,
`verifyPinnedSnapshot`, `createRealProduct`, `deriveAnchor`, `playbookFor` and
`runPlaybook` functions, with snapshot checks before and after each playbook.
Gortex's read-only daemon and RepoWise's loopback API were started locally;
Codebase Memory used its pinned one-shot CLI. The source digests and immutable
receipt-file hashes are in `manifest.json`. Each receipt was checked against its
scheduled tool/parameters and its normalized output hash was recomputed.

Successful invocation does not establish useful retrieval, correct causal
analysis or a complete repair. The saved outputs include empty/no-match
responses. This gate establishes availability and receipt integrity; it supplies
no scored LLM answer and no product ranking. Full model smoke remains a
separate gate, subject to the authorized US$5 total catalog-cost ceiling.
